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

// All routes below require JWT + admin role
router.use(protect, adminOnly);

// ─── POST /api/admin/geofence ─────────────────────────────────────────────────
router.post(
  '/geofence',
  [
    body('latitude').isFloat({ min: -90, max: 90 }),
    body('longitude').isFloat({ min: -180, max: 180 }),
    body('allowedRadius').isInt({ min: 10, max: 100000 }).withMessage('Radius 10–100000 metres'),
    body('allowedDomain').trim().notEmpty().withMessage('Domain required (e.g. psgtech.ac.in)')
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { latitude, longitude, allowedRadius, allowedDomain } = req.body;
    try {
      // Use explicit typed values to prevent NoSQL operator injection
      const config = await GeofenceConfig.findOneAndUpdate(
        {},
        {
          latitude: parseFloat(latitude),
          longitude: parseFloat(longitude),
          allowedRadius: parseInt(allowedRadius, 10),
          allowedDomain: String(allowedDomain).toLowerCase().trim(),
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

// ─── GET /api/admin/geofence ──────────────────────────────────────────────────
router.get('/geofence', async (_req, res) => {
  try {
    const config = await GeofenceConfig.findOne().sort({ updatedAt: -1 });
    return res.json({ success: true, config });
  } catch (err) {
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

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

    // Date filters
    const filter = {};
    if (req.query.date) {
      const date = new Date(req.query.date);
      const nextDay = new Date(date);
      nextDay.setDate(date.getDate() + 1);
      filter.clockInTime = { $gte: date, $lt: nextDay };
    } else if (req.query.startDate && req.query.endDate) {
      filter.clockInTime = {
        $gte: new Date(req.query.startDate),
        $lte: new Date(req.query.endDate)
      };
    }

    // Filter by user email or name
    if (req.query.search) {
      const searchRegex = new RegExp(req.query.search, 'i');
      const users = await User.find({
        $or: [{ name: searchRegex }, { email: searchRegex }]
      }).select('_id').lean();
      filter.userId = { $in: users.map(u => u._id) };
    }

    const [records, total] = await Promise.all([
      AttendanceRecord.find(filter)
        .populate('userId', 'name email')
        .sort({ clockInTime: -1 })
        .skip(skip)
        .limit(limit)
        .lean(),
      AttendanceRecord.countDocuments(filter)
    ]);

    return res.json({ success: true, page, total, records });
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
      today: {
        count: todayRecords.length,
        records: todayRecords
      },
      yesterday: {
        count: yesterdayRecords.length,
        records: yesterdayRecords
      },
      totalActiveUsers: totalUsers
    });
  } catch (err) {
    console.error('[ADMIN SUMMARY]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/admin/export-csv ────────────────────────────────────────
router.get('/export-csv', async (req, res) => {
  try {
    // Date filters
    const filter = {};
    if (req.query.date) {
      const date = new Date(req.query.date);
      const nextDay = new Date(date);
      nextDay.setDate(date.getDate() + 1);
      filter.clockInTime = { $gte: date, $lt: nextDay };
    } else if (req.query.startDate && req.query.endDate) {
      filter.clockInTime = {
        $gte: new Date(req.query.startDate),
        $lte: new Date(req.query.endDate)
      };
    }

    const records = await AttendanceRecord.find(filter)
      .populate('userId', 'name email')
      .sort({ clockInTime: -1 })
      .lean();

    // Build CSV manually for zero extra deps in prod
    const header = 'Name,Email,Clock-In,Clock-Out,Duration (min),Auto Clock-Out,Android ID\n';
    const rows = records.map((r) => {
      const name = r.userId ? r.userId.name : '';
      const email = r.userId ? r.userId.email : r.email;
      const inn = r.clockInTime ? new Date(r.clockInTime).toISOString() : '';
      const out = r.clockOutTime ? new Date(r.clockOutTime).toISOString() : '';
      const dur = r.durationMinutes != null ? r.durationMinutes : '';
      const auto = r.autoClockOut ? 'Yes' : 'No';
      const aid = r.androidId || '';
      return `"${name}","${email}","${inn}","${out}","${dur}","${auto}","${aid}"`;
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
