const nodemailer = require('nodemailer');

const transporter = nodemailer.createTransport({
  host: process.env.SMTP_HOST,
  port: parseInt(process.env.SMTP_PORT || '587', 10),
  secure: false,
  auth: {
    user: process.env.SMTP_USER,
    pass: process.env.SMTP_PASS
  }
});

/**
 * Send a 6-digit OTP to the given email address.
 * @param {string} email  recipient
 * @param {string} otp    6-digit code
 */
async function sendOtpEmail(email, otp) {
  const mailOptions = {
    from: process.env.OTP_FROM || 'GPunch <noreply@gpunch.app>',
    to: email,
    subject: 'GPunch – Your One-Time Password',
    text: `Your GPunch OTP is: ${otp}\n\nThis code expires in ${process.env.OTP_EXPIRY_MINUTES || 10} minutes.\nDo not share this code with anyone.`,
    html: `
      <div style="font-family:sans-serif;max-width:480px;margin:auto">
        <h2 style="color:#1a73e8">GPunch Verification</h2>
        <p>Your one-time password is:</p>
        <div style="font-size:2.5rem;font-weight:bold;letter-spacing:0.3rem;color:#202124;margin:16px 0">${otp}</div>
        <p style="color:#5f6368;font-size:0.9rem">
          This code expires in <strong>${process.env.OTP_EXPIRY_MINUTES || 10} minutes</strong>.<br>
          Do not share this code with anyone.
        </p>
      </div>
    `
  };

  await transporter.sendMail(mailOptions);
}

/**
 * Generate a random 6-digit OTP string.
 * @returns {string}
 */
function generateOtp() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

module.exports = { sendOtpEmail, generateOtp };
