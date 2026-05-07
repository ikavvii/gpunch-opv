/**
 * Auth Routes
 * POST /api/auth/register      – domain-restricted registration; sends OTP
 * POST /api/auth/verify-otp    – OTP verification + device binding
 * POST /api/auth/login         – email/OTP login for existing users
 * POST /api/auth/resend-otp    – resend OTP
 */
const express = require('express');
const { body, validationResult } = require('express-validator');
const rateLimit = require('express-rate-limit');
const User = require('../models/User');
const GeofenceConfig = require('../models/GeofenceConfig');
const AuditLog = require('../models/AuditLog');
const { generateOtp, sendOtpEmail } = require('../utils/mailer');
const jwt = require('jsonwebtoken');

const router = express.Router();

// Stricter rate-limit for auth endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 20,
  message: { success: false, message: 'Too many auth requests, please try again later.' }
});

router.use(authLimiter);

// ─── Helper ───────────────────────────────────────────────────────────────────
function signToken(userId) {
  return jwt.sign({ id: userId }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || '7d'
  });
}

function configuredDomains(config) {
  const domains = Array.isArray(config.allowedDomains) ? config.allowedDomains : [];
  const all = domains.length ? domains : [config.allowedDomain];
  return all.map(domain => String(domain).trim().replace(/^@/, '').toLowerCase()).filter(Boolean);
}

// ─── POST /api/auth/register ──────────────────────────────────────────────────
router.post(
  '/register',
  [
    body('name').trim().notEmpty().withMessage('Name is required'),
    body('email').isEmail().normalizeEmail().withMessage('Valid email required'),
    body('androidId').trim().notEmpty().withMessage('androidId is required')
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { name, email, androidId } = req.body;

    try {
      // Load active domain config
      const config = await GeofenceConfig.findOne().sort({ updatedAt: -1 });
      if (!config) {
        return res.status(503).json({
          success: false,
          message: 'System not yet configured. Contact administrator.'
        });
      }

      const emailDomain = email.split('@')[1]?.toLowerCase();
      const allowedDomains = configuredDomains(config);
      if (!allowedDomains.includes(emailDomain)) {
        // Log the rejected registration attempt for admin visibility (TC-01/TC-13)
        await AuditLog.create({
          userId: null,
          email: String(email),
          androidId: String(androidId),
          eventType: 'INVALID_DOMAIN',
          ipAddress: req.ip,
          metadata: { attemptedDomain: emailDomain, allowedDomains }
        }).catch(() => {}); // best-effort – don't block the response
        return res.status(403).json({
          success: false,
          message: `Registration is restricted to: ${allowedDomains.map(d => `@${d}`).join(', ')}.`
        });
      }

      // Check for duplicate – email is sanitized by express-validator normalizeEmail()
      let user = await User.findOne({ email: String(email) });
      if (user && user.isVerified) {
        return res.status(409).json({ success: false, message: 'Email already registered.' });
      }

      const otp = generateOtp();
      const otpExpiresAt = new Date(
        Date.now() + (parseInt(process.env.OTP_EXPIRY_MINUTES || '10') * 60 * 1000)
      );

      if (user) {
        // Refresh OTP for unverified user
        user.name = name;
        user.androidId = androidId;
        user.otp = otp;
        user.otpExpiresAt = otpExpiresAt;
      } else {
        user = new User({ name, email, androidId, otp, otpExpiresAt });
      }

      await user.save();
      await sendOtpEmail(email, otp);

      return res.status(200).json({
        success: true,
        message: `OTP sent to ${email}. Valid for ${process.env.OTP_EXPIRY_MINUTES || 10} minutes.`
      });
    } catch (err) {
      console.error('[REGISTER]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── POST /api/auth/verify-otp ────────────────────────────────────────────────
router.post(
  '/verify-otp',
  [
    body('email').isEmail().normalizeEmail(),
    body('otp').isLength({ min: 6, max: 6 }).withMessage('OTP must be 6 digits'),
    body('androidId').trim().notEmpty()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { email, otp, androidId } = req.body;

    try {
      const user = await User.findOne({ email: String(email) });
      if (!user) {
        return res.status(404).json({ success: false, message: 'User not found.' });
      }

      // Compare OTP as strings to prevent type coercion attacks
      if (typeof otp !== 'string' || user.otp !== otp) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId: String(androidId),
          eventType: 'OTP_FAILED',
          ipAddress: req.ip,
          metadata: { reason: 'wrong_otp' }
        }).catch(() => {});
        return res.status(400).json({ success: false, message: 'Invalid OTP.' });
      }

      if (!user.otpExpiresAt || user.otpExpiresAt < new Date()) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId: String(androidId),
          eventType: 'OTP_FAILED',
          ipAddress: req.ip,
          metadata: { reason: 'otp_expired' }
        }).catch(() => {});
        return res.status(400).json({ success: false, message: 'OTP has expired.' });
      }

      // Device binding: if already bound, enforce it
      if (user.androidId && user.androidId !== androidId) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId: String(androidId),
          eventType: 'UNAUTHORIZED_DEVICE',
          ipAddress: req.ip,
          metadata: { boundDevice: user.androidId, phase: 'verify_otp' }
        }).catch(() => {});
        return res.status(403).json({
          success: false,
          message: 'Device mismatch. Contact admin to reset your device binding.'
        });
      }

      user.androidId = androidId;
      user.isVerified = true;
      user.otp = null;
      user.otpExpiresAt = null;
      await user.save();

      const token = signToken(user._id);
      return res.status(200).json({
        success: true,
        message: 'Account verified successfully.',
        token,
        user: {
          id: user._id,
          name: user.name,
          email: user.email,
          role: user.role,
          androidId: user.androidId
        }
      });
    } catch (err) {
      console.error('[VERIFY-OTP]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── POST /api/auth/login ─────────────────────────────────────────────────────
// Passwordless: send a fresh OTP for login
router.post(
  '/login',
  [
    body('email').isEmail().normalizeEmail(),
    body('androidId').trim().notEmpty()
  ],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { email, androidId } = req.body;

    try {
      const user = await User.findOne({ email: String(email) });
      if (!user || !user.isVerified) {
        return res.status(404).json({ success: false, message: 'Account not found or not verified.' });
      }

      if (!user.isActive) {
        return res.status(403).json({ success: false, message: 'Account is deactivated.' });
      }

      // Enforce device binding
      if (user.androidId && user.androidId !== androidId) {
        await AuditLog.create({
          userId: user._id,
          email: user.email,
          androidId: String(androidId),
          eventType: 'UNAUTHORIZED_DEVICE',
          ipAddress: req.ip,
          metadata: { boundDevice: user.androidId }
        }).catch(() => {});
        return res.status(403).json({
          success: false,
          message: 'Unrecognised device. Contact admin to reset your device binding.'
        });
      }

      const otp = generateOtp();
      const otpExpiresAt = new Date(
        Date.now() + (parseInt(process.env.OTP_EXPIRY_MINUTES || '10') * 60 * 1000)
      );
      user.otp = otp;
      user.otpExpiresAt = otpExpiresAt;
      await user.save();
      await sendOtpEmail(email, otp);

      return res.status(200).json({
        success: true,
        message: `OTP sent to ${email}.`
      });
    } catch (err) {
      console.error('[LOGIN]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

// ─── POST /api/auth/resend-otp ────────────────────────────────────────────────
router.post(
  '/resend-otp',
  [body('email').isEmail().normalizeEmail()],
  async (req, res) => {
    const errors = validationResult(req);
    if (!errors.isEmpty())
      return res.status(400).json({ success: false, errors: errors.array() });

    const { email } = req.body;
    try {
      const user = await User.findOne({ email: String(email) });
      if (!user) {
        return res.status(404).json({ success: false, message: 'User not found.' });
      }

      const otp = generateOtp();
      const otpExpiresAt = new Date(
        Date.now() + (parseInt(process.env.OTP_EXPIRY_MINUTES || '10') * 60 * 1000)
      );
      user.otp = otp;
      user.otpExpiresAt = otpExpiresAt;
      await user.save();
      await sendOtpEmail(email, otp);

      return res.status(200).json({ success: true, message: 'OTP resent successfully.' });
    } catch (err) {
      console.error('[RESEND-OTP]', err);
      return res.status(500).json({ success: false, message: 'Internal server error' });
    }
  }
);

module.exports = router;
