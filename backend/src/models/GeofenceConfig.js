const mongoose = require('mongoose');

/**
 * Stores global geofence configuration.
 * There is always exactly ONE active document; admin upserts it.
 */
const geofenceConfigSchema = new mongoose.Schema(
  {
    latitude: { type: Number, required: true },
    longitude: { type: Number, required: true },
    allowedRadius: { type: Number, required: true }, // metres
    allowedDomain: { type: String, required: true, lowercase: true, trim: true },
    updatedBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User' }
  },
  { timestamps: true }
);

module.exports = mongoose.model('GeofenceConfig', geofenceConfigSchema);
