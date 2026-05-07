/**
 * Admin Routes  (all require JWT + admin role)
 *
 * POST  /api/admin/geofence          – upsert geofence + allowed domain
 * GET   /api/admin/geofence          – fetch current geofence config
 * POST  /api/admin/reset-device/:id  – clear androidId for a user
 * GET   /api/admin/users             – list all users
 * GET   /api/admin/audit-logs        – paginated audit logs
 * GET   /api/admin/attendance        – attendance report with date filters
 * GET   /api/admin/attendance/summary – today/yesterday summary
 * GET   /api/admin/export-csv        – download CSV report
 * POST  /api/admin/seed              – seed first admin (only if no admin exists)
 */
const express = require('express');
const { body, validationResult } = require('express-validator');
const mongoose = require('mongoose');
const { protect, adminOnly } = require('../middleware/auth');
const User = require('../models/User');
const GeofenceConfig = require('../models/GeofenceConfig');
const AttendanceRecord = require('../models/AttendanceRecord');
const AuditLog = require('../models/AuditLog');

const VALID_EVENT_TYPES = [
  'MOCK_LOCATION',
  'UNAUTHORIZED_DEVICE',
  'OUT_OF_BOUNDS',
  'INVALID_DOMAIN',
  'OTP_FAILED',
  'SUSPICIOUS_ACTIVITY'
];

const router = express.Router();

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function buildDateFilter(query) {
  if (query.date) {
    const date = new Date(query.date);
    if (Number.isNaN(date.getTime())) return { error: 'Invalid date filter.' };
    const nextDay = new Date(date);
    nextDay.setDate(date.getDate() + 1);
    return { filter: { clockInTime: { $gte: date, $lt: nextDay } } };
  }

  if (query.startDate && query.endDate) {
    const start = new Date(query.startDate);
    const end = new Date(query.endDate);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
      return { error: 'Invalid date range.' };
    }
    return { filter: { clockInTime: { $gte: start, $lte: end } } };
  }

  return { filter: {} };
}

function csvCell(value) {
  const raw = value == null ? '' : String(value);
  return `"${raw.replace(/"/g, '""')}"`;
}

function userKey(record) {
  const user = record.userId;
  if (!user) return null;
  return String(user._id || user);
}

function summarizeAttendance(records) {
  const uniqueUsers = new Set(records.map(userKey).filter(Boolean));
  const totalMinutes = records.reduce((sum, record) => sum + (record.durationMinutes || 0), 0);
  return {
    uniqueUsers: uniqueUsers.size,
    totalPunches: records.length,
    activeNow: records.filter(record => !record.clockOutTime).length,
    totalMinutes,
    totalHours: Number((totalMinutes / 60).toFixed(2))
  };
}

function normalizeDomains(value) {
  const raw = Array.isArray(value) ? value : String(value || '').split(',');
  return [...new Set(
    raw
      .map(domain => String(domain).trim().replace(/^@/, '').toLowerCase())
      .filter(Boolean)
  )];
}

// ─── Seed first admin (bootstrap – no auth required) ─────────────────────────
router.post(
  '/seed',
  [
    body('name').trim().notEmpty(),
    body('email').isEmail().normalizeEmail(),
    body('secret').notEmpty()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    // Guard with a shared secret (set in .env)
    if (req.body.secret !== process.env.ADMIN_SEED_SECRET) {
      return res.status(403).json({ success: false, message: 'Invalid seed secret.' });
    }

    const existingAdmin = await User.findOne({ role: 'admin' });
    if (existingAdmin) {
      return res.status(409).json({ success: false, message: 'Admin already exists.' });
    }

    const admin = await User.create({
      name: req.body.name,
      email: req.body.email,
      role: 'admin',
      isVerified: true,
      isActive: true
    });

    return res.status(201).json({
      success: true,
      message: 'Admin seeded. Use /api/auth/login to obtain a JWT.',
      admin: { id: admin._id, email: admin.email }
    });
  }
);

// Authenticated users need the active geofence for client-side pre-checks.
router.get('/geofence', protect, async (_req, res) => {
  try {
    const config = await GeofenceConfig.findOne().sort({ updatedAt: -1 });
    return res.json({ success: true, config });
  } catch (err) {
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// All routes below require JWT + admin role
router.use(protect, adminOnly);

// ─── POST /api/admin/geofence ─────────────────────────────────────────────────
router.post(
  '/geofence',
  [
    body('latitude').isFloat({ min: -90, max: 90 }),
    body('longitude').isFloat({ min: -180, max: 180 }),
    body('allowedRadius').isInt({ min: 10, max: 100000 }).withMessage('Radius 10–100000 metres'),
    body('allowedDomain').trim().notEmpty().withMessage('At least one domain required (e.g. psgtech.ac.in)'),
    body('isActive').optional().isBoolean()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { latitude, longitude, allowedRadius, allowedDomain } = req.body;
    const allowedDomains = normalizeDomains(req.body.allowedDomains || allowedDomain);
    if (!allowedDomains.length) {
      return res.status(400).json({ success: false, message: 'At least one domain is required.' });
    }
    try {
      // Use explicit typed values to prevent NoSQL operator injection
      const config = await GeofenceConfig.findOneAndUpdate(
        {},
        {
          latitude: parseFloat(latitude),
          longitude: parseFloat(longitude),
          allowedRadius: parseInt(allowedRadius, 10),
          allowedDomain: allowedDomains[0],
          allowedDomains,
          isActive: req.body.isActive !== false,
          updatedBy: req.user._id
        },
        { upsert: true, new: true, setDefaultsOnInsert: true }
      );
      return res.json({ success: true, message: 'Geofence updated.', config });
    } catch (err) {
      console.error('[ADMIN GEOFENCE]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── POST /api/admin/reset-device/:userId ─────────────────────────────
router.post('/reset-device/:userId', async (req, res) => {
  // Validate ObjectId to prevent NoSQL injection through URL param
  if (!mongoose.Types.ObjectId.isValid(req.params.userId)) {
    return res.status(400).json({ success: false, message: 'Invalid user ID.' });
  }
  try {
    const user = await User.findById(req.params.userId);
    if (!user) return res.status(404).json({ success: false, message: 'User not found.' });

    const previousDevice = user.androidId;
    user.androidId = null;
    await user.save();

    return res.json({
      success: true,
      message: `Device binding cleared for ${user.email}. They can re-register with a new device.`,
      previousDevice
    });
  } catch (err) {
    console.error('[ADMIN RESET-DEVICE]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/users ─────────────────────────────────────────────
router.get('/users', async (req, res) => {
  try {
    const page = Math.max(1, parseInt(req.query.page || '1', 10));
    const limit = Math.min(100, parseInt(req.query.limit || '20', 10));
    const skip = (page - 1) * limit;

    const [users, total] = await Promise.all([
      User.find().sort({ createdAt: -1 }).skip(skip).limit(limit).lean(),
      User.countDocuments()
    ]);
    return res.json({ success: true, page, total, users });
  } catch (err) {
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/audit-logs ────────────────────────────────────────────
router.get('/audit-logs', async (req, res) => {
  try {
    const page = Math.max(1, parseInt(req.query.page || '1', 10));
    const limit = Math.min(100, parseInt(req.query.limit || '20', 10));
    const skip = (page - 1) * limit;

    const filter = {};
    if (req.query.eventType) {
      // Whitelist eventType to prevent NoSQL operator injection
      const et = String(req.query.eventType);
      if (VALID_EVENT_TYPES.includes(et)) {
        filter.eventType = et;
      }
    }

    const [logs, total] = await Promise.all([
      AuditLog.find(filter).sort({ createdAt: -1 }).skip(skip).limit(limit).lean(),
      AuditLog.countDocuments(filter)
    ]);
    return res.json({ success: true, page, total, logs });
  } catch (err) {
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/attendance ───────────────────────────────────────────
router.get('/attendance', async (req, res) => {
  try {
    const page = Math.max(1, parseInt(req.query.page || '1', 10));
    const limit = Math.min(100, parseInt(req.query.limit || '20', 10));
    const skip = (page - 1) * limit;

    const { filter, error } = buildDateFilter(req.query);
    if (error) return res.status(400).json({ success: false, message: error });

    // Filter by user email or name
    if (req.query.search) {
      const searchRegex = new RegExp(escapeRegex(req.query.search), 'i');
      const users = await User.find({
        $or: [{ name: searchRegex }, { email: searchRegex }]
      }).select('_id').lean();
      filter.userId = { $in: users.map(u => u._id) };
    }

    const [records, total, totals] = await Promise.all([
      AttendanceRecord.find(filter)
        .populate('userId', 'name email')
        .sort({ clockInTime: -1 })
        .skip(skip)
        .limit(limit)
        .lean(),
      AttendanceRecord.countDocuments(filter),
      AttendanceRecord.aggregate([
        { $match: filter },
        {
          $group: {
            _id: null,
            totalMinutes: { $sum: { $ifNull: ['$durationMinutes', 0] } },
            uniqueUserIds: { $addToSet: '$userId' },
            activeNow: {
              $sum: {
                $cond: [{ $eq: [{ $ifNull: ['$clockOutTime', null] }, null] }, 1, 0]
              }
            }
          }
        },
        {
          $project: {
            _id: 0,
            totalMinutes: 1,
            uniqueUsers: { $size: '$uniqueUserIds' },
            activeNow: 1
          }
        }
      ])
    ]);
    const reportTotals = totals[0] || { totalMinutes: 0, uniqueUsers: 0, activeNow: 0 };

    return res.json({
      success: true,
      page,
      total,
      uniqueUsers: reportTotals.uniqueUsers,
      activeNow: reportTotals.activeNow,
      totalMinutes: reportTotals.totalMinutes,
      totalHours: Number((reportTotals.totalMinutes / 60).toFixed(2)),
      records
    });
  } catch (err) {
    console.error('[ADMIN ATTENDANCE]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/attendance/summary ──────────────────────────────────
router.get('/attendance/summary', async (req, res) => {
  try {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const tomorrow = new Date(today);
    tomorrow.setDate(tomorrow.getDate() + 1);

    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);

    const [todayRecords, yesterdayRecords, totalUsers] = await Promise.all([
      AttendanceRecord.find({
        clockInTime: { $gte: today, $lt: tomorrow }
      }).populate('userId', 'name email').lean(),
      AttendanceRecord.find({
        clockInTime: { $gte: yesterday, $lt: today }
      }).populate('userId', 'name email').lean(),
      User.countDocuments({ isActive: true })
    ]);

    return res.json({
      success: true,
      today: { ...summarizeAttendance(todayRecords), records: todayRecords },
      yesterday: { ...summarizeAttendance(yesterdayRecords), records: yesterdayRecords },
      totalActiveUsers: totalUsers
    });
  } catch (err) {
    console.error('[ADMIN SUMMARY]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/attendance/absentees ─────────────────────────────
router.get('/attendance/absentees', async (req, res) => {
  try {
    const { filter, error } = buildDateFilter({ date: req.query.date || new Date().toISOString().slice(0, 10) });
    if (error) return res.status(400).json({ success: false, message: error });

    const records = await AttendanceRecord.find(filter).select('userId').lean();
    const presentIds = records.map(r => r.userId).filter(Boolean);
    const users = await User.find({
      isActive: true,
      role: 'user',
      _id: { $nin: presentIds }
    }).select('name email role isActive isVerified androidId').sort({ name: 1 }).lean();

    return res.json({
      success: true,
      date: req.query.date || new Date().toISOString().slice(0, 10),
      total: users.length,
      users
    });
  } catch (err) {
    console.error('[ADMIN ABSENTEES]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/export-csv ────────────────────────────────────────
router.get('/export-csv', async (req, res) => {
  try {
    const { filter, error } = buildDateFilter(req.query);
    if (error) return res.status(400).json({ success: false, message: error });

    const records = await AttendanceRecord.find(filter)
      .populate('userId', 'name email')
      .sort({ clockInTime: -1 })
      .lean();

    // Build CSV manually for zero extra deps in prod
    const header = 'Name,Email,Punch In,Punch Out,Duration (min),Auto Punch Out,Android ID\n';
    const rows = records.map((r) => {
      const name = r.userId ? r.userId.name : '';
      const email = r.userId ? r.userId.email : r.email;
      const inn = r.clockInTime ? new Date(r.clockInTime).toISOString() : '';
      const out = r.clockOutTime ? new Date(r.clockOutTime).toISOString() : '';
      const dur = r.durationMinutes != null ? r.durationMinutes : '';
      const auto = r.autoClockOut ? 'Yes' : 'No';
      const aid = r.androidId || '';
      return [name, email, inn, out, dur, auto, aid].map(csvCell).join(',');
    });

    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', `attachment; filename="gpunch-attendance-${Date.now()}.csv"`);
    return res.send(header + rows.join('\n'));
  } catch (err) {
    console.error('[EXPORT-CSV]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

module.exports = router;
