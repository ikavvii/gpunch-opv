const mongoose = require('mongoose');

const attendanceSchema = new mongoose.Schema(
  {
    userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
    email: { type: String, required: true },

    // Clock-in data
    clockInTime: { type: Date, default: null },
    clockInLat: { type: Number, default: null },
    clockInLng: { type: Number, default: null },

    // Clock-out data
    clockOutTime: { type: Date, default: null },
    clockOutLat: { type: Number, default: null },
    clockOutLng: { type: Number, default: null },

    // Duration in minutes (calculated on clock-out)
    durationMinutes: { type: Number, default: null },

    // Flags
    autoClockOut: { type: Boolean, default: false }, // triggered by Foreground Service
    androidId: { type: String, required: true }
  },
  { timestamps: true }
);

// Index for efficient per-user + date queries
attendanceSchema.index({ userId: 1, clockInTime: -1 });

module.exports = mongoose.model('AttendanceRecord', attendanceSchema);
