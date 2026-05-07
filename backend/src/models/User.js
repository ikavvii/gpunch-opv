const mongoose = require('mongoose');

const userSchema = new mongoose.Schema(
  {
    name: { type: String, required: true, trim: true },
    email: {
      type: String,
      required: true,
      unique: true,
      lowercase: true,
      trim: true
    },
    role: { type: String, enum: ['user', 'admin'], default: 'user' },

    // Device binding – set on first successful OTP verification
    androidId: { type: String, default: null },

    // OTP fields
    otp: { type: String, default: null },
    otpExpiresAt: { type: Date, default: null },
    isVerified: { type: Boolean, default: false },

    // Optional admin-managed fields
    isActive: { type: Boolean, default: true }
  },
  { timestamps: true }
);

// Never return OTP in JSON responses
userSchema.set('toJSON', {
  transform(_doc, ret) {
    delete ret.otp;
    delete ret.otpExpiresAt;
    return ret;
  }
});

module.exports = mongoose.model('User', userSchema);
