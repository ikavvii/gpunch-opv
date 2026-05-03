/**
 * GPunch Backend – Entry Point
 * Express + MongoDB Atlas + JWT
 */
require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const mongoose = require('mongoose');

const authRoutes = require('./routes/auth');
const punchRoutes = require('./routes/punch');
const adminRoutes = require('./routes/admin');
const auditRoutes = require('./routes/audit');

const app = express();

// ─── Security Middleware ──────────────────────────────────────────────────────
app.use(helmet());
app.use(cors());
app.use(express.json({ limit: '10kb' }));

// Global rate-limiter: 100 requests / 15 min per IP
const globalLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 100,
  standardHeaders: true,
  legacyHeaders: false,
  message: { success: false, message: 'Too many requests, please try again later.' }
});
app.use(globalLimiter);

// ─── Health Check ─────────────────────────────────────────────────────────────
app.get('/health', (_req, res) => res.json({ status: 'ok', ts: Date.now() }));

// ─── API Routes ───────────────────────────────────────────────────────────────
app.use('/api/auth', authRoutes);
app.use('/api/punch', punchRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/audit', auditRoutes);

// ─── 404 Handler ──────────────────────────────────────────────────────────────
app.use((_req, res) => res.status(404).json({ success: false, message: 'Route not found' }));

// ─── Global Error Handler ─────────────────────────────────────────────────────
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error('[ERROR]', err);
  res.status(err.status || 500).json({
    success: false,
    message: err.message || 'Internal server error'
  });
});

// ─── Database + Server Boot ───────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;

async function start() {
  try {
    await mongoose.connect(process.env.MONGODB_URI);
    console.log('[DB] Connected to MongoDB Atlas');
    app.listen(PORT, () => console.log(`[SERVER] GPunch API running on port ${PORT}`));
  } catch (err) {
    console.error('[FATAL] Cannot connect to MongoDB:', err.message);
    process.exit(1);
  }
}

start();

module.exports = app; // export for testing
