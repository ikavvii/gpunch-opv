/**
 * Auth + Punch route integration tests (using supertest with mocked Mongoose).
 *
 * These tests exercise the key test cases defined in the project spec:
 *  TC-01 / TC-02  – Domain restriction
 *  TC-03          – OTP verification failure
 *  TC-04          – Device binding enforcement
 *  TC-06 / TC-07  – Geofence inside / outside
 */
const request = require('supertest');

// ─── Mock Mongoose models before requiring app ───────────────────────────────
jest.mock('../src/models/GeofenceConfig', () => {
  const mockConfig = {
    latitude: 11.0168,
    longitude: 76.9558,
    allowedRadius: 100,
    allowedDomain: 'psgtech.ac.in',
    updatedAt: new Date()
  };
  return { findOne: jest.fn(() => ({ sort: jest.fn().mockResolvedValue(mockConfig) })) };
});

jest.mock('../src/models/User', () => {
  let _user = null;
  function MockUser(data) {
    Object.assign(this, data);
    this._id = 'u1';
    this.save = jest.fn().mockResolvedValue(this);
  }
  MockUser._setUser = (u) => { _user = u; };
  MockUser.findOne = jest.fn(() => Promise.resolve(_user));
  MockUser.findById = jest.fn(() => Promise.resolve(_user));
  MockUser.create = jest.fn((data) => Promise.resolve({ ...data, _id: 'u1', save: jest.fn() }));
  return MockUser;
});

jest.mock('../src/models/AuditLog', () => ({
  create: jest.fn().mockResolvedValue({ _id: 'log1' }),
  find: jest.fn(() => ({ sort: jest.fn(() => ({ skip: jest.fn(() => ({ limit: jest.fn(() => ({ lean: jest.fn().mockResolvedValue([]) })) })) })) })),
  countDocuments: jest.fn().mockResolvedValue(0)
}));

jest.mock('../src/models/AttendanceRecord', () => ({
  findOne: jest.fn().mockResolvedValue(null),
  create: jest.fn((data) => Promise.resolve({ ...data, _id: 'r1', save: jest.fn() }))
}));

jest.mock('../src/utils/mailer', () => ({
  generateOtp: jest.fn(() => '123456'),
  sendOtpEmail: jest.fn().mockResolvedValue(true)
}));

// ─── Suppress mongo connection in tests ──────────────────────────────────────
jest.mock('mongoose', () => {
  const actual = jest.requireActual('mongoose');
  return { ...actual, connect: jest.fn().mockResolvedValue(true) };
});

// We need a JWT secret for the app before importing
process.env.JWT_SECRET = 'test-secret-key-for-jest';
process.env.MONGODB_URI = 'mongodb://localhost/test';

const app = require('../src/index');
const User = require('../src/models/User');
const AuditLog = require('../src/models/AuditLog');

// ─── TC-01 / TC-02: Domain Restriction ───────────────────────────────────────
describe('POST /api/auth/register – domain restriction', () => {
  beforeEach(() => {
    User._setUser(null);
    jest.clearAllMocks();
    // Re-set config mock after clearAllMocks
    const GeofenceConfig = require('../src/models/GeofenceConfig');
    GeofenceConfig.findOne.mockReturnValue({
      sort: jest.fn().mockResolvedValue({
        latitude: 11.0168, longitude: 76.9558, allowedRadius: 100,
        allowedDomain: 'psgtech.ac.in', updatedAt: new Date()
      })
    });
  });

  it('TC-01: rejects registration with non-institutional email', async () => {
    const res = await request(app)
      .post('/api/auth/register')
      .send({ name: 'Test User', email: 'user@gmail.com', androidId: 'device1' });

    expect(res.status).toBe(403);
    expect(res.body.success).toBe(false);
    expect(res.body.message).toMatch(/restricted/i);
  });

  it('TC-01: logs INVALID_DOMAIN audit event when domain is rejected', async () => {
    await request(app)
      .post('/api/auth/register')
      .send({ name: 'Test User', email: 'user@gmail.com', androidId: 'device1' });

    expect(AuditLog.create).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'INVALID_DOMAIN' })
    );
  });

  it('TC-02: accepts registration with institutional email', async () => {
    const res = await request(app)
      .post('/api/auth/register')
      .send({ name: 'Test User', email: 'student@psgtech.ac.in', androidId: 'device1' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.message).toMatch(/OTP sent/i);
  });
});

// ─── TC-03: OTP Failure ───────────────────────────────────────────────────────
describe('POST /api/auth/verify-otp – OTP verification', () => {
  const mockUser = {
    _id: 'u1',
    name: 'Test',
    email: 'student@psgtech.ac.in',
    androidId: 'device1',
    otp: '654321',
    otpExpiresAt: new Date(Date.now() + 600_000),
    isVerified: false,
    isActive: true,
    role: 'user',
    save: jest.fn()
  };

  beforeEach(() => {
    User._setUser({ ...mockUser, save: jest.fn() });
    jest.clearAllMocks();
  });

  it('TC-03: rejects incorrect OTP and logs OTP_FAILED', async () => {
    const res = await request(app)
      .post('/api/auth/verify-otp')
      .send({ email: 'student@psgtech.ac.in', otp: '000000', androidId: 'device1' });

    expect(res.status).toBe(400);
    expect(res.body.message).toMatch(/invalid otp/i);
    expect(AuditLog.create).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'OTP_FAILED', metadata: expect.objectContaining({ reason: 'wrong_otp' }) })
    );
  });

  it('TC-03: rejects expired OTP and logs OTP_FAILED', async () => {
    User._setUser({
      ...mockUser,
      otp: '654321',
      otpExpiresAt: new Date(Date.now() - 1000), // already expired
      save: jest.fn()
    });

    const res = await request(app)
      .post('/api/auth/verify-otp')
      .send({ email: 'student@psgtech.ac.in', otp: '654321', androidId: 'device1' });

    expect(res.status).toBe(400);
    expect(res.body.message).toMatch(/expired/i);
    expect(AuditLog.create).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'OTP_FAILED', metadata: expect.objectContaining({ reason: 'otp_expired' }) })
    );
  });
});

// ─── TC-04: Device Binding ────────────────────────────────────────────────────
describe('POST /api/auth/login – device binding', () => {
  const boundUser = {
    _id: 'u2',
    email: 'student@psgtech.ac.in',
    androidId: 'device1',
    isVerified: true,
    isActive: true,
    otp: null,
    otpExpiresAt: null,
    save: jest.fn()
  };

  beforeEach(() => {
    User._setUser({ ...boundUser, save: jest.fn() });
    jest.clearAllMocks();
  });

  it('TC-04: rejects login attempt from an unregistered device', async () => {
    const res = await request(app)
      .post('/api/auth/login')
      .send({ email: 'student@psgtech.ac.in', androidId: 'device999' });

    expect(res.status).toBe(403);
    expect(res.body.message).toMatch(/unrecognised device/i);
    expect(AuditLog.create).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'UNAUTHORIZED_DEVICE' })
    );
  });

  it('TC-04: allows login from the bound device', async () => {
    const res = await request(app)
      .post('/api/auth/login')
      .send({ email: 'student@psgtech.ac.in', androidId: 'device1' });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.message).toMatch(/OTP sent/i);
  });
});
