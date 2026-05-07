/**
 * Audit Route
 * POST /api/audit  – receive security events from Android client
 *                    (mock location, unauthorized device, etc.)
 */
const express = require('express');
const { body, validationResult } = require('express-validator');
const { protect } = require('../middleware/auth');
const AuditLog = require('../models/AuditLog');

const router = express.Router();

const VALID_EVENT_TYPES = [
  'MOCK_LOCATION',
  'UNAUTHORIZED_DEVICE',
  'OUT_OF_BOUNDS',
  'INVALID_DOMAIN',
  'OTP_FAILED',
  'SUSPICIOUS_ACTIVITY'
];

router.post(
  '/',
  protect,
  [
    body('eventType').isIn(VALID_EVENT_TYPES).withMessage('Invalid eventType'),
    body('latitude').optional().isFloat({ min: -90, max: 90 }),
    body('longitude').optional().isFloat({ min: -180, max: 180 }),
    body('androidId').trim().notEmpty()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { eventType, latitude, longitude, androidId, metadata } = req.body;
    const user = req.user;

    try {
      const log = await AuditLog.create({
        userId: user._id,
        email: user.email,
        androidId,
        eventType,
        latitude: latitude ?? null,
        longitude: longitude ?? null,
        ipAddress: req.ip,
        metadata: metadata || {}
      });

      console.warn(`[SECURITY] ${eventType} – user: ${user.email} – ip: ${req.ip}`);

      return res.status(201).json({ success: true, logId: log._id });
    } catch (err) {
      console.error('[AUDIT]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

module.exports = router;
