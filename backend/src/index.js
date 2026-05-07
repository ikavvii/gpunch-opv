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
mongoose.set('bufferCommands', false);

// ─── Security Middleware ──────────────────────────────────────────────
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

// ─── Health Check ──────────────────────────────────────────────────────
function mongoState() {
  const states = ['disconnected', 'connected', 'connecting', 'disconnecting'];
  return states[mongoose.connection?.readyState] || 'unknown';
}

app.get('/health', (_req, res) => {
  const db = mongoState();
  res.status(db === 'connected' ? 200 : 503).json({
    status: db === 'connected' ? 'ok' : 'degraded',
    db,
    ts: Date.now()
  });
});

app.use('/api', (_req, res, next) => {
  if (process.env.NODE_ENV === 'test' || mongoose.connection?.readyState === 1) return next();
  return res.status(503).json({
    success: false,
    message: 'Database is not connected. Check MongoDB Atlas DNS/network access or use a reachable local MongoDB URI.'
  });
});

// ─── API Routes ───────────────────────────────────────────────────────
app.use('/api/auth', authRoutes);
app.use('/api/punch', punchRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/audit', auditRoutes);

// ─── 404 Handler ──────────────────────────────────────────────────────
app.use((_req, res) => res.status(404).json({ success: false, message: 'Route not found' }));

// ─── Global Error Handler ──────────────────────────────────────────────
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error('[ERROR]', err);
  res.status(err.status || 500).json({
    success: false,
    message: err.message || 'Internal server error'
  });
});

// ─── Database + Server Boot ───────────────────────────────────────────
const PORT = process.env.PORT || 3000;
let retryTimer = null;

function mongoHint(err) {
  const message = err?.message || String(err);
  if (message.includes('querySrv') || message.includes('ENOTFOUND') || message.includes('ECONNREFUSED')) {
    return [
      message,
      'Hint: this is a DNS/network problem while resolving the mongodb+srv Atlas record.',
      'Try a different DNS resolver/network, whitelist your IP in Atlas, or set MONGODB_URI to a reachable local MongoDB such as mongodb://127.0.0.1:27017/gpunch-opv.'
    ].join('\n');
  }
  return message;
}

async function connectMongo() {
  if (!process.env.MONGODB_URI) {
    throw new Error('MONGODB_URI is not configured');
  }

  await mongoose.connect(process.env.MONGODB_URI, {
    serverSelectionTimeoutMS: 10_000
  });
}

function scheduleMongoRetry() {
  if (retryTimer || process.env.NODE_ENV === 'production') return;

  retryTimer = setInterval(async () => {
    if (mongoose.connection.readyState !== 0) return;
    try {
      console.log('[DB] Retrying MongoDB connection...');
      await connectMongo();
      console.log('[DB] Connected to MongoDB');
      clearInterval(retryTimer);
      retryTimer = null;
    } catch (err) {
      console.error('[DB] Retry failed:', mongoHint(err));
    }
  }, 15_000);
}

async function start() {
  const server = app.listen(PORT, () => console.log(`[SERVER] GPunch API running on port ${PORT}`));

  try {
    await connectMongo();
    console.log('[DB] Connected to MongoDB');
  } catch (err) {
    console.error('[DB] Cannot connect to MongoDB:', mongoHint(err));
    if (process.env.NODE_ENV === 'production') {
      server.close(() => process.exit(1));
      return;
    }
    console.warn('[DB] Development mode: server remains up and will retry MongoDB in the background.');
    scheduleMongoRetry();
  }
}

if (require.main === module) {
  start();
}

module.exports = app;
module.exports.start = start;
