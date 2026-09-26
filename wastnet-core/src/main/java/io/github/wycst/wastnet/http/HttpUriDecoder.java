package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.util.Utils;

import java.util.*;

public final class HttpUriDecoder {

    static final int MAX_CACHE_SIZE = 16 << 10; // 16kb
    final boolean strict;
    static final byte WHITE_SPACE = 32;
    static final byte HEX_TOKEN = '%';
    HttpBuf contentBa;

    String uri;
    String queryString;
    boolean asciiMode = true;
    byte codecState;
    int hexByte;

    /**
     * Parameter parsing mode flag.
     *
     * <p>Three scenarios:</p>
     * <ul>
     *   <li>URI without query string (e.g., /path/to/resource):
     *       parameter = false, '?' is ignored, '=' and '&' are not treated as parameter delimiters</li>
     *   <li>URI with query string (e.g., /path?a=1&b=2):
     *       parameter = true, '?' triggers the flag to become true, '=' and '&' are treated as delimiters</li>
     *   <li>POST body with application/x-www-form-urlencoded:
     *       parameter = true (set by constructor), '=' and '&' are treated as parameter delimiters</li>
     * </ul>
     */
    boolean parameter;
    Map<String, List<String>> parameters;

    String paramName;

    public HttpUriDecoder(boolean strict) {
        this(strict, false);
    }

    public HttpUriDecoder(boolean strict, boolean parameter) {
        this.strict = strict;
        this.parameter = parameter;
        this.contentBa = new HttpBuf(64);
    }

    /**
     * Decode URI or form-urlencoded data (incremental).
     * <p>
     * Can be called multiple times with partial data chunks.
     * Caller must call endCodec() after all data has been fed to finalize parsing.
     *
     * @param bytes data chunk to decode
     */
    public HttpUriDecoder codec(byte[] bytes) {
        for (byte b : bytes) {
            codec(b);
        }
        return this;
    }

    /**
     * Decode URI or form-urlencoded data with offset (incremental).
     * <p>
     * Can be called multiple times with partial data chunks.
     * Caller must call endCodec() after all data has been fed to finalize parsing.
     *
     * @param bytes  data chunk to decode
     * @param offset start offset in bytes array
     * @param count  number of bytes to process
     */
    public HttpUriDecoder codec(byte[] bytes, int offset, int count) {
        for (int i = offset, len = offset + count; i < len; ++i) {
            codec(bytes[i]);
        }
        return this;
    }

    public void codec(byte b) {
        if (codecState == 0) {
            // ? or '#' : split index of uri and paramepter
            // %: begin of coder (%hex)
            switch (b) {
                case '?': {
                    if (!parameter) {
                        parameter = true;
                        uri = contentAsString();
                        resetContentBa();
                    } else {
                        contentBa.write(b);
                    }
                    return;
                }
                case '+': {
                    contentBa.write(WHITE_SPACE);
                    return;
                }
                case '%': {
                    codecState = 1;
                    return;
                }
                case '#': {
                    // ignore mode
                    codecState = -1;
                    return;
                }
                case '=': {
                    if (parameter) {
                        // parameter -> name
                        if (paramName == null) {
                            paramName = contentAsString();
                            resetContentBa();
                            return;
                        }
                        // '=' inside a value is a literal char (e.g. base64/JWT); write as-is
                         contentBa.write(b);
                         return;
                    }
                }
                case '&': {
                    if (parameter) {
                        nextParam();
                        return;
                    }
                }
                default: {
                    contentBa.write(b);
                }
            }
        } else if (codecState == 1) {
            if (Utils.hexNibble((hexByte = b) & 0xFF) != -1) {
                codecState = 2;
            } else {
                if (strict) {
                    throw new IllegalArgumentException("HttpUriDecoder Error: Illegal hex characters in escape (%) " + (char) b);
                } else {
                    codecState = 0;
                    contentBa.write(HEX_TOKEN);
                    codec(b);
                }
            }
        } else if (codecState == 2) {
            codecState = 0;
            byte lowHex = Utils.hexNibble(b & 0xFF);
            if (lowHex != -1) {
                byte highHex = Utils.hexNibble(hexByte);
                byte decoded = (byte) (highHex << 4 | lowHex);
                if (decoded < 0) {
                    asciiMode = false;
                }
                contentBa.write(decoded);
            } else {
                if (strict) {
                    throw new IllegalArgumentException("HttpUriDecoder Error: Illegal hex characters in escape (%) " + (char) hexByte + (char) b);
                } else {
                    contentBa.write((byte) '%');
                    contentBa.write((byte) hexByte);
                    codec(b);
                }
            }
        }
    }

    /**
     * Finalize the decoding process.
     * <p>
     * This method must be called after all data has been fed via codec() methods.
     * It handles the final pending content:
     * <ul>
     *   <li>For URI-only mode (parameter=false): saves accumulated content as the URI</li>
     *   <li>For parameter mode (parameter=true): flushes the last pending parameter key/value pair</li>
     * </ul>
     * After this call, getUri(), getParameters(), and getQueryString() will return valid results.
     */
    public void endCodec() {
        if (!parameter) {
            // URI-only mode: content is the request URI path
            uri = contentAsString();
        } else {
            // Parameter mode: flush the last pending parameter
            nextParam();
        }
        resetContentBa();
    }

    private void resetContentBa() {
        contentBa.clear();
        asciiMode = true;
    }

    private void nextParam() {
        if (paramName != null) {
            // parameter -> value
            nextParam(paramName, contentAsString());
            resetContentBa();
            paramName = null;
        } else {
            nextParam(contentAsString(), null);
            resetContentBa();
        }
    }

    private String contentAsString() {
        return asciiMode ? contentBa.toISO_8859_1_string() : contentBa.toString(Utils.UTF_8);
    }

    private void nextParam(String key, String value) {
        if (parameters == null) {
            parameters = new HashMap<>(8);
        }
        List<String> values = parameters.computeIfAbsent(key, k -> new ArrayList<>(1));
        values.add(value);
    }

    public Map<String, List<String>> getParameters() {
        return parameters == null ? Collections.emptyMap() : parameters;
    }

    public void setRawUri(String uri) {
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }

    public void reset() {
        codecState = 0;
        parameter = false;
        asciiMode = true;
        if (contentBa.capacity() < MAX_CACHE_SIZE) {
            contentBa.clear();
        } else {
            contentBa = new HttpBuf();
        }
        uri = null;
        queryString = null;
        paramName = null;
        parameters = null;
    }
}
