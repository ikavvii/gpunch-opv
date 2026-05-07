const mongoose = require('mongoose');

/**
 * Security audit log.
 * Written when: mock location detected, unauthorized device, out-of-bounds punch.
 */
const auditLogSchema = new mongoose.Schema(
  {
    userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
    email: { type: String, default: 'unknown' },
    androidId: { type: String, default: null },
    eventType: {
      type: String,
      enum: [
        'MOCK_LOCATION',
        'UNAUTHORIZED_DEVICE',
        'OUT_OF_BOUNDS',
        'INVALID_DOMAIN',
        'OTP_FAILED',
        'SUSPICIOUS_ACTIVITY'
      ],
      required: true
    },
    latitude: { type: Number, default: null },
    longitude: { type: Number, default: null },
    distanceFromZone: { type: Number, default: null }, // metres
    ipAddress: { type: String, default: null },
    metadata: { type: mongoose.Schema.Types.Mixed, default: {} }
  },
  { timestamps: true }
);

auditLogSchema.index({ userId: 1, createdAt: -1 });
auditLogSchema.index({ eventType: 1, createdAt: -1 });

module.exports = mongoose.model('AuditLog', auditLogSchema);
