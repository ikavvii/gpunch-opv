/**
 * Unit tests for the Haversine distance utility.
 */
const { haversineDistance } = require('../src/utils/haversine');

describe('haversineDistance', () => {
  it('returns 0 for identical coordinates', () => {
    expect(haversineDistance(11.0168, 76.9558, 11.0168, 76.9558)).toBe(0);
  });

  it('calculates distance between two known points', () => {
    // Small northward shift ~0.0032 degrees lat ≈ 356m
    const dist = haversineDistance(11.0168, 76.9558, 11.0200, 76.9558);
    expect(dist).toBeGreaterThan(300);
    expect(dist).toBeLessThan(400);
  });

  it('correctly computes ~111km for 1 degree of latitude', () => {
    const dist = haversineDistance(0, 0, 1, 0);
    expect(dist).toBeGreaterThan(110_000);
    expect(dist).toBeLessThan(112_000);
  });
});
