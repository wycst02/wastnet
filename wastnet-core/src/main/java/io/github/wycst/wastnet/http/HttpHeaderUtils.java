package io.github.wycst.wastnet.http;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP Header utility processing class
 * Provides core functions such as Header formatting and merging strategies
 *
 * @author wangyc
 * @since 2024
 */
public class HttpHeaderUtils {

    static HeaderConfig headerConfig = HeaderConfig.defaultConfig();

    final static byte MERGE_COMMA_SEPARATED = 0;
    final static byte MERGE_ALLOW_DUPLICATES = 1;

    final static byte FORMAT_LOWERCASE = 0;
    final static byte FORMAT_TITLE_CASE = 1;

    // Pre-calculated Server header line bytes (initialized with lowercase "server")
    private static final byte[] SERVER_HEADER_LINE_BYTES;

    private static final Map<String, String> STANDARD_HEADERS_MAP = new HashMap<>();
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        // Initialize all configurations from environment
        initializeConfigFromEnvironment();

        // Initialize Server header line bytes based on initial configuration
        SERVER_HEADER_LINE_BYTES = ((isTitleCase() ? "Server" : "server") + ": " + HTTPServer.SERVER + "/" + HTTPServer.VERSION + "\r\n").getBytes();

        // Initialize standard header name mapping
        String[][] STANDARD_HEADERS = {
                {"accept", "Accept"},
                {"accept-charset", "Accept-Charset"},
                {"accept-encoding", "Accept-Encoding"},
                {"accept-language", "Accept-Language"},
                {"accept-ranges", "Accept-Ranges"},
                {"age", "Age"},
                {"allow", "Allow"},
                {"authorization", "Authorization"},
                {"cache-control", "Cache-Control"},
                {"connection", "Connection"},
                {"content-disposition", "Content-Disposition"},
                {"content-encoding", "Content-Encoding"},
                {"content-length", "Content-Length"},
                {"content-type", "Content-Type"},
                {"cookie", "Cookie"},
                {"date", "Date"},
                {"etag", "ETag"},
                {"expect", "Expect"},
                {"expires", "Expires"},
                {"from", "From"},
                {"host", "Host"},
                {"if-match", "If-Match"},
                {"if-modified-since", "If-Modified-Since"},
                {"if-none-match", "If-None-Match"},
                {"if-range", "If-Range"},
                {"if-unmodified-since", "If-Unmodified-Since"},
                {"keep-alive", "Keep-Alive"},
                {"last-modified", "Last-Modified"},
                {"location", "Location"},
                {"max-forwards", "Max-Forwards"},
                {"proxy-authenticate", "Proxy-Authenticate"},
                {"proxy-authorization", "Proxy-Authorization"},
                {"range", "Range"},
                {"referer", "Referer"},
                {"retry-after", "Retry-After"},
                {"server", "Server"},
                {"set-cookie", "Set-Cookie"},
                {"strict-transport-security", "Strict-Transport-Security"},
                {"transfer-encoding", "Transfer-Encoding"},
                {"upgrade", "Upgrade"},
                {"user-agent", "User-Agent"},
                {"vary", "Vary"},
                {"via", "Via"},
                {"www-authenticate", "WWW-Authenticate"},
                {"x-frame-options", "X-Frame-Options"},
                {"sec-websocket-accept", "Sec-WebSocket-Accept"},
                {"sec-websocket-key", "Sec-WebSocket-Key"},
        };
        for (String[] entry : STANDARD_HEADERS) {
            STANDARD_HEADERS_MAP.put(entry[0], entry[1]);
        }

        // Initialize MIME type mapping
        String[][] MIME_ENTRIES = {
                {"html", HttpHeaderValues.TEXT_HTML},
                {"htm", HttpHeaderValues.TEXT_HTML},
                {"txt", HttpHeaderValues.TEXT_PLAIN},
                {"css", HttpHeaderValues.TEXT_CSS},
                {"csv", "text/csv"},
                {"ics", "text/calendar"},
                {"markdown", "text/markdown"},
                {"md", "text/markdown"},
                {"js", "application/javascript"},
                {"mjs", "application/javascript"},
                {"json", HttpHeaderValues.APPLICATION_JSON},
                {"map", "application/json"},
                {"xml", HttpHeaderValues.APPLICATION_XML},
                {"xhtml", HttpHeaderValues.APPLICATION_XHTML},
                {"xsd", "application/xml"},
                {"xsl", "application/xslt+xml"},
                {"png", "image/png"},
                {"jpg", "image/jpeg"},
                {"jpeg", "image/jpeg"},
                {"gif", "image/gif"},
                {"ico", "image/x-icon"},
                {"svg", HttpHeaderValues.IMAGE_SVG_XML},
                {"webp", "image/webp"},
                {"bmp", "image/bmp"},
                {"tiff", "image/tiff"},
                {"tif", "image/tiff"},
                {"mp3", "audio/mpeg"},
                {"wav", "audio/wav"},
                {"ogg", "audio/ogg"},
                {"m4a", "audio/mp4"},
                {"aac", "audio/aac"},
                {"mp4", "video/mp4"},
                {"avi", "video/x-msvideo"},
                {"mov", "video/quicktime"},
                {"wmv", "video/x-ms-wmv"},
                {"flv", "video/x-flv"},
                {"webm", "video/webm"},
                {"pdf", "application/pdf"},
                {"zip", "application/zip"},
                {"gz", "application/gzip"},
                {"tar", "application/x-tar"},
                {"rar", "application/vnd.rar"},
                {"7z", "application/x-7z-compressed"},
                {"exe", "application/octet-stream"},
                {"bin", HttpHeaderValues.APPLICATION_OCTET_STREAM},
                {"dll", "application/octet-stream"},
                {"doc", "application/msword"},
                {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                {"xls", "application/vnd.ms-excel"},
                {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
                {"ppt", "application/vnd.ms-powerpoint"},
                {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
                {"woff", "font/woff"},
                {"woff2", HttpHeaderValues.FONT_WOFF2},
                {"ttf", "font/ttf"},
                {"otf", "font/otf"},
                {"yaml", "application/yaml"},
                {"yml", "application/yaml"},
                {"toml", "application/toml"},
                {"properties", "text/x-java-properties"},
                {"log", "text/plain"},
                {"rtf", "application/rtf"},
        };
        for (String[] entry : MIME_ENTRIES) {
            MIME_TYPES.put(entry[0], entry[1]);
        }
    }

    /**
     * Initialize configuration from environment variables
     * Supported environment variables:
     * - wastnet.http.header.format: lowercase(default) or titlecase
     * - wastnet.http.header.merge: comma_separated(default) or allow_duplicates
     */
    private static void initializeConfigFromEnvironment() {
        try {
            // Read configuration values
            String formatValue = System.getProperty("wastnet.http.header.format", "");
            String mergeValue = System.getProperty("wastnet.http.header.merge", "");

            byte formatStrategy = FORMAT_LOWERCASE;
            byte mergeStrategy = MERGE_COMMA_SEPARATED;

            if ("titlecase".equalsIgnoreCase(formatValue.trim())) {
                formatStrategy = FORMAT_TITLE_CASE;
            }
            if ("allow_duplicates".equalsIgnoreCase(mergeValue.trim())) {
                mergeStrategy = MERGE_ALLOW_DUPLICATES;
            }

            // Create and set configuration
            headerConfig = new HeaderConfig(mergeStrategy, formatStrategy);

        } catch (Exception e) {
            // Use default configuration when parsing fails
            headerConfig = HeaderConfig.defaultConfig();
        }
    }

    /**
     * Get date as byte array for specified timestamp (date only, without header prefix)
     * Creates a new byte array with RFC 7231 date format
     * Format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes)
     *
     * @param timestampMillis timestamp in milliseconds
     * @return byte array representation of GMT date for the given timestamp
     */
    public static byte[] getDateHeaderBytes(long timestampMillis) {
        return HttpDate.getDateHeaderBytes(timestampMillis);
    }

    /**
     * Get date as string for specified timestamp
     *
     * @param timestampMillis timestamp in milliseconds
     * @return string representation of GMT date for the given timestamp
     */
    public static String getDateHeaderValue(long timestampMillis) {
        return new String(HttpDate.getDateHeaderBytes(timestampMillis));
    }

    public static boolean isTitleCase() {
        return headerConfig.formatStrategy == FORMAT_TITLE_CASE;
    }

    public static void setHeaderConfig(HeaderConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        headerConfig = config;
        // Reinitialize CONTENT-related constants when configuration changes
        HttpHeaderNormalized.reinitializeContentHeaders();
        // Update Server header case when configuration changes
        updateServerHeaderCase();
        // Update Date header case when configuration changes
        HttpDate.updateDateHeaderCase();
    }

    /**
     * Reset to default configuration
     */
    public static void resetHeaderConfig() {
        setHeaderConfig(HeaderConfig.defaultConfig());
    }

    /**
     * Check if current configuration uses comma-separated merge strategy
     */
    public static boolean isCommaSeparated() {
        return headerConfig.mergeStrategy == MERGE_COMMA_SEPARATED;
    }

    /**
     * Check if current configuration allows duplicate header keys
     */
    public static boolean isAllowDuplicates() {
        return headerConfig.mergeStrategy == MERGE_ALLOW_DUPLICATES;
    }

    /**
     * Convert header key to standard format
     */
    public static String toStandardFormat(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }

        String standardFormat = STANDARD_HEADERS_MAP.get(key.toLowerCase());
        return standardFormat != null ? standardFormat : toTitleCase(key);
    }

    /**
     * Normalize header key using global configuration
     */
    public static String normalizeHeaderKey(String key) {
        if (headerConfig.formatStrategy == FORMAT_TITLE_CASE) {
            return toStandardFormat(key);
        } else {
            return key.toLowerCase();
        }
    }

    /**
     * Get MIME type based on file extension
     *
     * @param extension file extension (without dot)
     * @return corresponding MIME type, or null if not found
     */
    public static String getMimeType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        return MIME_TYPES.get(extension.toLowerCase());
    }

    /**
     * Get MIME type based on filename
     *
     * @param filename file name
     * @return corresponding MIME type, or null if not found
     */
    public static String getMimeTypeByFilename(String filename) {
        return getMimeTypeByFilename(filename, null);
    }

    /**
     * Get MIME type based on filename, with a fallback default.
     *
     * @param filename   file name
     * @param defaultType default MIME type returned when lookup fails
     * @return corresponding MIME type, or {@code defaultType} if not found
     */
    public static String getMimeTypeByFilename(String filename, String defaultType) {
        if (filename == null || filename.isEmpty()) {
            return defaultType;
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > -1 && dotIndex < filename.length() - 1) {
            String extension = filename.substring(dotIndex + 1);
            String mime = getMimeType(extension);
            if (mime != null) return mime;
        }
        return defaultType;
    }

    /**
     * Simple Title Case conversion implementation
     */
    public static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder(input.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < input.length(); ++i) {
            char c = input.charAt(i);
            if (c == '-' || c == '_') {
                result.append('-');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    /**
     * Convert ASCII characters in the specified range of byte array to Title Case format
     * First letter uppercase, letters after hyphen also uppercase, others lowercase
     * Example: "content-type" -> "Content-Type"
     *
     * @param buf    byte array
     * @param offset start offset
     * @param len    conversion length
     */
    public static void toTitleCase(byte[] buf, int offset, int len) {
        boolean capitalizeNext = true;
        for (int i = offset; i < offset + len; ++i) {
            byte b = buf[i];
            if (b == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                // Convert to uppercase
                buf[i] = (byte) (b >= 'a' && b <= 'z' ? b & ~0x20 : b);
                capitalizeNext = false;
            } else {
                // Convert to lowercase
                buf[i] = (byte) (b >= 'A' && b <= 'Z' ? b | 0x20 : b);
            }
        }
    }

    /**
     * Convert all ASCII characters in the specified range of byte array to lowercase
     * Example: "Content-Type" -> "content-type"
     *
     * @param buf    byte array
     * @param offset start offset
     * @param len    conversion length
     */
    public static void toLowerCase(byte[] buf, int offset, int len) {
        for (int i = offset; i < offset + len; ++i) {
            byte b = buf[i];
            // Only convert uppercase letters to lowercase
            buf[i] = (byte) (b >= 'A' && b <= 'Z' ? b | 0x20 : b);
        }
    }

    /**
     * Normalize header key buffer according to current configuration strategy
     * Applies Title Case or lowercase transformation based on headerConfig.formatStrategy
     *
     * @param buf    byte array containing header key
     * @param offset start position in buffer
     * @param len    length of header key data
     */
    public static void normalizedKeyBuffer(byte[] buf, int offset, int len) {
        if (headerConfig.formatStrategy == FORMAT_TITLE_CASE) {
            toTitleCase(buf, offset, len);
        } else {
            toLowerCase(buf, offset, len);
        }
    }

    /**
     * Immutable Header configuration
     * Used to manage HeaderMergeStrategy and HeaderFormatStrategy configuration
     */
    public static class HeaderConfig {

        public final byte mergeStrategy;
        public final byte formatStrategy;

        public HeaderConfig(byte mergeStrategy, byte formatStrategy) {
            this.mergeStrategy = mergeStrategy;
            this.formatStrategy = formatStrategy;
        }

        /**
         * Create default configuration
         */
        public static HeaderConfig defaultConfig() {
            return new HeaderConfig(MERGE_COMMA_SEPARATED, FORMAT_LOWERCASE);
        }

        /**
         * Create standard compatible configuration
         */
        public static HeaderConfig standardConfig() {
            return new HeaderConfig(MERGE_COMMA_SEPARATED, FORMAT_TITLE_CASE);
        }
    }

    /**
     * Update Server header first byte based on configuration
     * Only modifies the first character ('s' to 'S' for titlecase, or 'S' to 's' for lowercase)
     */
    private static void updateServerHeaderCase() {
        if (isTitleCase()) {
            // Convert first letter to uppercase: 's' -> 'S'
            SERVER_HEADER_LINE_BYTES[0] = (byte) ('S');
        } else {
            // Convert first letter to lowercase: 'S' -> 's'
            SERVER_HEADER_LINE_BYTES[0] = (byte) ('s');
        }
    }

    /**
     * Get pre-calculated Server header line bytes
     */
    static byte[] getServerHeaderLineBytes() {
        return SERVER_HEADER_LINE_BYTES;
    }

}