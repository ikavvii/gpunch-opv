/**
 * Punch Routes
 * POST /api/punch/in    – clock in (geofence validated)
 * POST /api/punch/out   – clock out (manual or auto from Foreground Service)
 * GET  /api/punch/status – current punch status for authenticated user
 * GET  /api/punch/history – paginated attendance history
 */
const express = require('express');
const { body, validationResult } = require('express-validator');
const { protect } = require('../middleware/auth');
const AttendanceRecord = require('../models/AttendanceRecord');
const GeofenceConfig = require('../models/GeofenceConfig');
const AuditLog = require('../models/AuditLog');
const { haversineDistance } = require('../utils/haversine');

const router = express.Router();
router.use(protect);

// ─── POST /api/punch/in ───────────────────────────────────────────────────────
router.post(
  '/in',
  [
    body('latitude').isFloat({ min: -90, max: 90 }).withMessage('Invalid latitude'),
    body('longitude').isFloat({ min: -180, max: 180 }).withMessage('Invalid longitude'),
    body('androidId').trim().notEmpty().withMessage('androidId required')
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { latitude, longitude, androidId } = req.body;
    const user = req.user;

    try {
      // Device binding check
      if (user.androidId !== androidId) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId,
          eventType: 'UNAUTHORIZED_DEVICE',
          latitude,
          longitude,
          ipAddress: req.ip
        });
        return res.status(403).json({ success: false, message: 'Unauthorized device.' });
      }

      // Load geofence config
      const config = await GeofenceConfig.findOne().sort({ updatedAt: -1 });
      if (!config) {
        return res.status(503).json({ success: false, message: 'Geofence not configured.' });
      }

      const geofenceActive = config.isActive !== false;
      const distance = geofenceActive
        ? haversineDistance(config.latitude, config.longitude, latitude, longitude)
        : 0;

      if (geofenceActive && distance > config.allowedRadius) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId,
          eventType: 'OUT_OF_BOUNDS',
          latitude,
          longitude,
          distanceFromZone: Math.round(distance),
          ipAddress: req.ip,
          metadata: { allowedRadius: config.allowedRadius }
        });
        return res.status(403).json({
          success: false,
          message: `You are ${Math.round(distance)}m away from the work zone (max ${config.allowedRadius}m).`,
          distance: Math.round(distance),
          allowedRadius: config.allowedRadius
        });
      }

      // Prevent double clock-in
      const existing = await AttendanceRecord.findOne({
        userId: user._id,
        clockOutTime: null
      });
      if (existing) {
        return res.status(409).json({
          success: false,
          message: 'Already punched in. Please punch out first.',
          recordId: existing._id
        });
      }

      const record = await AttendanceRecord.create({
        userId: user._id,
        email: user.email,
        androidId,
        clockInTime: new Date(),
        clockInLat: latitude,
        clockInLng: longitude
      });

      return res.status(201).json({
        success: true,
        message: 'Punched in successfully.',
        record: {
          id: record._id,
          clockInTime: record.clockInTime,
          distance: Math.round(distance)
        },
        allowedRadius: config.allowedRadius,
        geofence: {
          latitude: config.latitude,
          longitude: config.longitude,
          allowedRadius: config.allowedRadius,
          isActive: geofenceActive
        }
      });
    } catch (err) {
      console.error('[PUNCH-IN]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── POST /api/punch/out ──────────────────────────────────────────────────────
router.post(
  '/out',
  [
    body('latitude').isFloat({ min: -90, max: 90 }),
    body('longitude').isFloat({ min: -180, max: 180 }),
    body('androidId').trim().notEmpty(),
    body('autoClockOut').optional().isBoolean()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { latitude, longitude, androidId, autoClockOut = false } = req.body;
    const user = req.user;

    try {
      if (user.androidId !== androidId) {
        return res.status(403).json({ success: false, message: 'Unauthorized device.' });
      }

      const record = await AttendanceRecord.findOne({
        userId: user._id,
        clockOutTime: null
      });

      if (!record) {
        return res.status(404).json({ success: false, message: 'No active punch-in found.' });
      }

      const clockOutTime = new Date();
      const durationMinutes = Math.round(
        (clockOutTime - record.clockInTime) / 60000
      );

      record.clockOutTime = clockOutTime;
      record.clockOutLat = latitude;
      record.clockOutLng = longitude;
      record.durationMinutes = durationMinutes;
      record.autoClockOut = Boolean(autoClockOut);
      await record.save();

      return res.status(200).json({
        success: true,
        message: autoClockOut ? 'Auto punched out after leaving the work zone.' : 'Punched out successfully.',
        record: {
          id: record._id,
          clockInTime: record.clockInTime,
          clockOutTime: record.clockOutTime,
          durationMinutes
        }
      });
    } catch (err) {
      console.error('[PUNCH-OUT]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── GET /api/punch/status ────────────────────────────────────────────────────
router.get('/status', async (req, res) => {
  try {
    const record = await AttendanceRecord.findOne({
      userId: req.user._id,
      clockOutTime: null
    });
    return res.json({
      success: true,
      isClockedIn: !!record,
      record: record || null
    });
  } catch (err) {
    console.error('[PUNCH-STATUS]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

// ─── GET /api/punch/history ───────────────────────────────────────────────────
router.get('/history', async (req, res) => {
  try {
    const page = Math.max(1, parseInt(req.query.page || '1', 10));
    const limit = Math.min(50, parseInt(req.query.limit || '20', 10));
    const skip = (page - 1) * limit;

    const [records, total] = await Promise.all([
      AttendanceRecord.find({ userId: req.user._id })
        .sort({ clockInTime: -1 })
        .skip(skip)
        .limit(limit)
        .lean(),
      AttendanceRecord.countDocuments({ userId: req.user._id })
    ]);

    return res.json({
      success: true,
      page,
      totalPages: Math.ceil(total / limit),
      total,
      records
    });
  } catch (err) {
    console.error('[PUNCH-HISTORY]', err);
    return res.status(500).json({ success: false, message: 'Internal server error' });
  }
});

module.exports = router;
