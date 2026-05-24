package com.att.tdp.issueflow.attachments.service;

/**
 * Tiny magic-byte sniffer used by {@link AttachmentService} as the
 * defense-in-depth half of spec 11 §1's MIME validation (Session 12 D2).
 *
 * <p>The {@code Content-Type} header on the multipart part is the
 * primary check; this class is the secondary "does the file actually
 * start with the right bytes?" check that catches a client claiming
 * {@code image/png} for a JavaScript payload renamed to {@code .png}.
 *
 * <p><b>Coverage matrix:</b>
 * <ul>
 *   <li>PNG — checked. {@code 89 50 4E 47 0D 0A 1A 0A} (8-byte signature).</li>
 *   <li>JPEG — checked. {@code FF D8 FF} (any JFIF/Exif variant).</li>
 *   <li>PDF — checked. {@code 25 50 44 46} ({@code %PDF}).</li>
 *   <li>text/plain — NOT checked. There is no reliable magic byte for
 *       arbitrary text files (UTF-8 BOM is optional, ASCII has none).
 *       Charset/encoding sniffing is its own can of worms; we accept
 *       the header at face value. Documented in service JavaDoc.</li>
 * </ul>
 *
 * <p><b>Input contract:</b> callers pass the FIRST ~8 bytes of the
 * uploaded file (via {@code MultipartFile.getInputStream()} read into
 * a small buffer). All methods return {@code true} when the prefix
 * matches, {@code false} otherwise — including when the buffer is too
 * short (a 0-byte upload that claims to be a PNG isn't a PNG).
 */
public final class MagicBytes {

    private static final byte[] PNG_SIG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] JPEG_SIG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] PDF_SIG = { 0x25, 0x50, 0x44, 0x46 };

    /** Caller buffer size needed to cover all the supported signatures. */
    public static final int SNIFF_BYTES = 8;

    private MagicBytes() {}

    public static boolean isPng(byte[] prefix) {
        return startsWith(prefix, PNG_SIG);
    }

    public static boolean isJpeg(byte[] prefix) {
        return startsWith(prefix, JPEG_SIG);
    }

    public static boolean isPdf(byte[] prefix) {
        return startsWith(prefix, PDF_SIG);
    }

    private static boolean startsWith(byte[] data, byte[] sig) {
        if (data == null || data.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (data[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
