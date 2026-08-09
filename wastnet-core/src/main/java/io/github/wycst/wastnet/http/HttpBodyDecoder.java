/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.util.Utils;

import java.nio.charset.Charset;
import java.util.*;

/**
 * HTTP body decoder for handling various content types.
 * Supports multipart/form-data, application/x-www-form-urlencoded,
 * application/json, and application/octet-stream.
 *
 * @author wangyc
 * @since 2024/3/4
 */
public abstract class HttpBodyDecoder {
    // ==================== Static constants ====================
    static final Log log = LogFactory.getLog(HttpBodyDecoder.class);
    protected static final String CRLFCRLF = "\r\n\r\n";
    // Static header bytes (lowercase for case-insensitive matching)
    protected static final byte[] CONTENT_DISPOSITION_BYTES = "content-disposition:".getBytes();
    protected static final byte[] CONTENT_TYPE_BYTES = "content-type:".getBytes();
    protected static final byte[] NAME_EQ_BYTES = "name=".getBytes();
    protected static final byte[] FILENAME_EQ_BYTES = "filename=".getBytes();

    // ==================== Instance fields ====================
    protected final String contentType;
    protected final String contentTypeLower;
    protected final Charset charset;
    // Lazy-decoded caches
    protected Map<String, List<MultipartField>> multipartFields;
    protected Map<String, List<String>> urlencodedParams;

    protected HttpBodyDecoder(String contentType) {
        this.contentType = contentType != null ? contentType : "";
        // toLowerCase returns the same string if all chars are already lowercase
        this.contentTypeLower = this.contentType.toLowerCase();
        this.charset = parseCharset();
    }

    /**
     * Create a new HttpBodyDecoder instance for a request.
     *
     * @param request the HttpRequest object
     * @return a new HttpBodyDecoder instance
     */
    public static HttpBodyDecoder of(HttpRequest request) {
        String contentType = request.getContentType();
        if (request.isStream()) {
            return new HttpBodyStreamDecoder(contentType, request.bodyStream());
        }
        return new HttpBodyDefaultDecoder(contentType, request.getBodyData());
    }

    /**
     * Parse charset from content type header.
     * Defaults to UTF-8 if not specified.
     */
    private Charset parseCharset() {
        if (contentType.isEmpty()) {
            return Utils.UTF_8;
        }
        String charsetName = extractContentTypeParam("charset=");
        if (charsetName != null) {
            return Utils.forCharsetName(charsetName.toLowerCase(), Utils.UTF_8);
        }
        return Utils.UTF_8;
    }

    /**
     * Extract a parameter value from content type header.
     * Handles quoted values and stops at semicolon or space.
     *
     * @param paramName the parameter name with equals sign (e.g. "charset=", "boundary=")
     * @return the parameter value, or null if not found
     */
    private String extractContentTypeParam(String paramName) {
        int index = contentTypeLower.indexOf(paramName);
        if (index == -1) {
            return null;
        }
        int start = index + paramName.length();
        int end;
        if (start < contentType.length() && contentType.charAt(start) == '"') {
            end = contentType.indexOf('"', start + 1);
            if (end == -1) {
                end = contentType.length();
            }
            return contentType.substring(start + 1, end);
        } else {
            end = start;
            while (end < contentType.length()) {
                char c = contentType.charAt(end);
                if (c == ';' || c == ' ') break;
                end++;
            }
            return contentType.substring(start, end);
        }
    }

    /**
     * Check if content type is multipart/form-data.
     *
     * @return true if content type is multipart/form-data
     */
    public final boolean isMultipart() {
        return contentTypeLower.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA);
    }

    /**
     * Check if content type is application/x-www-form-urlencoded.
     *
     * @return true if content type is application/x-www-form-urlencoded
     */
    public final boolean isFormUrlencoded() {
        return contentTypeLower.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
    }

    /**
     * Check if content type is JSON (application/json or text/json).
     *
     * @return true if content type is JSON
     */
    public final boolean isJson() {
        return contentTypeLower.startsWith(HttpHeaderValues.APPLICATION_JSON) ||
                contentTypeLower.startsWith(HttpHeaderValues.TEXT_JSON);
    }

    /**
     * Check if content type is application/octet-stream.
     *
     * @return true if content type is application/octet-stream
     */
    public final boolean isOctetStream() {
        return contentTypeLower.startsWith(HttpHeaderValues.APPLICATION_OCTET_STREAM);
    }

    /**
     * Get the content type.
     *
     * @return content type string
     */
    public final String getContentType() {
        return contentType;
    }

    /**
     * Get the charset.
     *
     * @return charset
     */
    public final Charset getCharset() {
        return charset;
    }

    /**
     * Decode multipart/form-data body and populate cache.
     * Subclasses should store parsed results in multipartFields.
     * If content type is not multipart or parsing fails, cache should be set to empty Map.
     */
    public final void decodeMultipartFields() {
        byte[] boundaryBytes;
        if (!isMultipart() || (boundaryBytes = extractBoundary()) == null) {
            multipartFields = Collections.emptyMap();
            return;
        }
        try {
            doDecodeMultipartFields(boundaryBytes);
        } catch (Exception e) {
            log.error("decodeMultipartFields error: {}", e.getMessage());
            // e.printStackTrace();
            multipartFields = Collections.emptyMap();
        }
    }

    /**
     * Decode multipart body with the given boundary.
     * Subclasses should store parsed results in multipartFields.
     *
     * @param boundaryBytes boundary bytes with "--" prefix
     */
    protected abstract void doDecodeMultipartFields(byte[] boundaryBytes) throws Exception;

    /**
     * Decode application/x-www-form-urlencoded body and populate cache.
     * Subclasses should store parsed results in urlencodedParams.
     * If content type is not form-urlencoded or parsing fails, cache should be set to empty Map.
     */
    protected abstract void decodeFormUrlencoded();

    // ==================== Lazy-decoded getter methods ====================

    /**
     * Get the first multipart field by name with lazy decoding.
     * Returns the first field if multiple fields have the same name.
     *
     * @param name field name
     * @return MultipartField, or null if field not found or content type is not multipart
     */
    public final MultipartField getMultipartField(String name) {
        if (!isMultipart()) {
            return null;
        }
        List<MultipartField> fields = getMultipartFields(name);
        return fields != null ? fields.get(0) : null;
    }

    /**
     * Get all multipart fields by name with lazy decoding.
     * Supports multiple fields with the same name (e.g., multiple file uploads).
     *
     * @param name field name
     * @return list of MultipartField (non-empty), or null if field not found or content type is not multipart
     */
    public final List<MultipartField> getMultipartFields(String name) {
        if (!isMultipart()) {
            return null;
        }
        if (multipartFields == null) {
            decodeMultipartFields();
        }
        return multipartFields.get(name);
    }

    /**
     * Get a multipart field value as string by name.
     * Uses the charset from content-type header.
     *
     * @param name field name
     * @return field value as string, or null if field not found, is a file field, or content type is not multipart
     */
    public final String getMultipartFieldValue(String name) {
        if (!isMultipart()) {
            return null;
        }
        MultipartField field = getMultipartField(name);
        if (field == null || field.isFile()) {
            return null;
        }
        return field.getDataAsString(charset);
    }

    /**
     * Get all multipart field values as string by name.
     * File fields are ignored, only returns values of form fields.
     *
     * @param name field name
     * @return list of field values (may be empty if all fields are files), empty list if field not found or content type is not multipart
     */
    public final List<String> getMultipartFieldValues(String name) {
        if (!isMultipart()) {
            return Collections.emptyList();
        }
        List<MultipartField> fields = getMultipartFields(name);
        if (fields == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>(fields.size());
        for (MultipartField field : fields) {
            if (!field.isFile()) {
                values.add(field.getDataAsString(charset));
            }
        }
        return values;
    }

    /**
     * Get a parameter value by name (supports both multipart and form-urlencoded).
     * For multipart, returns the field value as string.
     * For form-urlencoded, returns the first value for the parameter name.
     *
     * @param name parameter name
     * @return parameter value, or null if not found
     */
    public final String getParameter(String name) {
        if (isMultipart()) {
            return getMultipartFieldValue(name);
        } else if (isFormUrlencoded()) {
            return getUrlencodedParameter(name);
        }
        return null;
    }

    /**
     * Get all parameter values by name (supports both multipart and form-urlencoded).
     * For multipart, returns all field values for the name.
     * For form-urlencoded, returns all values for the parameter name.
     *
     * @param name parameter name
     * @return list of parameter values, empty list if not found
     */
    public final List<String> getParameterValues(String name) {
        if (isMultipart()) {
            return getMultipartFieldValues(name);
        } else if (isFormUrlencoded()) {
            return getUrlencodedParameterValues(name);
        }
        return Collections.emptyList();
    }

    /**
     * Get a urlencoded parameter value by name with lazy decoding.
     *
     * @param name parameter name
     * @return parameter value, or null if not found or not form-urlencoded
     */
    public final String getUrlencodedParameter(String name) {
        if (!isFormUrlencoded()) {
            return null;
        }
        List<String> values = getUrlencodedParameterValues(name);
        return values != null ? values.get(0) : null;
    }

    /**
     * Get all urlencoded parameter values by name with lazy decoding.
     *
     * @param name parameter name
     * @return list of parameter values (non-empty), or null if not found or not form-urlencoded
     */
    public final List<String> getUrlencodedParameterValues(String name) {
        if (!isFormUrlencoded()) {
            return null;
        }
        if (urlencodedParams == null) {
            decodeFormUrlencoded();
        }
        return urlencodedParams.get(name);
    }

    /**
     * Get all multipart fields as a flat list, in body order.
     * Package-private, used by HttpInternalRequest.getMultipartFields().
     *
     * @return sorted list of all MultipartField instances, empty list if not multipart
     */
    final List<MultipartField> getOrderedMultipartFields() {
        if (!isMultipart()) {
            return Collections.emptyList();
        }
        if (multipartFields == null) {
            decodeMultipartFields();
        }
        List<MultipartField> all = new ArrayList<MultipartField>();
        for (List<MultipartField> list : multipartFields.values()) {
            all.addAll(list);
        }
        if (all.size() > 1) {
            Collections.sort(all, (a, b) -> a.getIndex() - b.getIndex());
        }
        return all;
    }

    /**
     * Get all multipart field names with lazy decoding.
     *
     * @return set of field names, empty set if not multipart
     */
    public final Set<String> getMultipartFieldNames() {
        if (!isMultipart()) {
            return Collections.emptySet();
        }
        if (multipartFields == null) {
            decodeMultipartFields();
        }
        return multipartFields.keySet();
    }

    /**
     * Get all urlencoded parameter names with lazy decoding.
     *
     * @return set of parameter names, empty set if not form-urlencoded
     */
    public final Set<String> getUrlencodedParameterNames() {
        if (!isFormUrlencoded()) {
            return Collections.emptySet();
        }
        if (urlencodedParams == null) {
            decodeFormUrlencoded();
        }
        return urlencodedParams.keySet();
    }

    /**
     * Release resources and clear caches.
     * For stream-based implementations, this may clean up temporary files or memory buffers.
     * Getter methods should not be called after release.
     */
    void release() {
        if (multipartFields != null) {
            for (List<MultipartField> fields : multipartFields.values()) {
                for (MultipartField field : fields) {
                    field.release();
                }
            }
            multipartFields.clear();
            multipartFields = null;
        }
        if (urlencodedParams != null) {
            urlencodedParams.clear();
            urlencodedParams = null;
        }
    }

    /**
     * Extract boundary bytes from content type header.
     *
     * @return boundary bytes with "--" prefix, or null if not found
     */
    protected final byte[] extractBoundary() {
        String boundary = extractContentTypeParam("boundary=");
        if (boundary == null || boundary.isEmpty()) {
            return null;
        }
        byte[] boundaryBytes = boundary.getBytes(charset);
        byte[] result = new byte[2 + boundaryBytes.length];
        result[0] = '-';
        result[1] = '-';
        System.arraycopy(boundaryBytes, 0, result, 2, boundaryBytes.length);
        return result;
    }

    /**
     * Build bad character table for Boyer-Moore algorithm.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Boyer%E2%80%93Moore_string-search_algorithm">Boyer-Moore Algorithm (Wikipedia)</a>
     */
    protected static int[] buildBadCharTable(byte[] pattern) {
        int patternLen = pattern.length;
        int[] badChar = new int[256];
        Arrays.fill(badChar, patternLen);
        for (int i = 0; i < patternLen - 1; ++i) {
            badChar[pattern[i] & 0xFF] = patternLen - 1 - i;
        }
        return badChar;
    }

    /**
     * Find pattern in data using Boyer-Moore algorithm with pre-built badChar table.
     * Implements the bad character rule from Boyer-Moore string search algorithm.
     *
     * <p>Reference: Boyer, R.S. and Moore, J.S., "A fast string searching algorithm",
     * Communications of the ACM 20(10):762-772, 1977.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Boyer%E2%80%93Moore_string-search_algorithm">Boyer-Moore Algorithm (Wikipedia)</a>
     */
    protected static int findBytes(byte[] data, int start, int end, byte[] pattern, int[] badChar) {
        final int patternLen = pattern.length, maxPos = end - patternLen;
        if (patternLen == 0 || start > maxPos) return -1;

        int i = start;
        while (i <= maxPos) {
            // After loop: j = mismatch position, k = matched count
            int j = patternLen - 1, k = 0;
            byte c = 0;
            while (j >= 0 && (c = data[i + j]) == pattern[j]) {
                --j;
                ++k;
            }
            if (j < 0) {
                return i;
            }
            // Bad character rule: skip = badChar[c] - k
            // badChar[c]: distance from c's rightmost position to pattern end
            int skip = badChar[c & 0xFF] - k;
            i += Math.max(1, skip);
        }
        return -1;
    }

    /**
     * Check if data starts with boundary.
     */
    protected static boolean startsWithBoundary(byte[] data, byte[] boundary) {
        int boundaryLen = boundary.length;
        if (boundaryLen > data.length) return false;
        for (int i = 0; i < boundaryLen; ++i) {
            if (data[i] != boundary[i]) return false;
        }
        return true;
    }

    /**
     * Case-insensitive match for header name prefix.
     *
     * @param data   source data
     * @param start  start position
     * @param end    end position (exclusive)
     * @param target lowercase ASCII bytes (e.g., CONTENT_DISPOSITION_BYTES)
     */
    protected static boolean matchIgnoreCase(byte[] data, int start, int end, byte[] target) {
        int len = target.length;
        if (start + len > end) return false;
        for (int j = 0; j < len; j++) {
            if ((data[start + j] | 0x20) != target[j]) return false;
        }
        return true;
    }

    /**
     * Parse multipart headers in one pass, extracting name, filename, and content-type.
     * IMPORTANT: For streaming decoders, data must be copied before calling this method
     * to avoid buffer reuse issues.
     *
     * @param data  header data (must be stable, not reused buffer)
     * @param start start position
     * @param end   end position (exclusive)
     * @return HttpBuf array: [name, filename, contentType], null values if not found
     */
    protected static HttpBuf[] parseMultipartHeaders(byte[] data, int start, int end) {
        int i = start;
        HttpBuf name = null, filename = null, contentType = null;
        while (i < end) {
            int lineEnd = findNewlineGuaranteed(data, i);

            if (matchIgnoreCase(data, i, lineEnd, CONTENT_DISPOSITION_BYTES)) {
                int colonPos = i + CONTENT_DISPOSITION_BYTES.length;
                while (data[colonPos] == ' ') colonPos++;

                int pos = colonPos;
                while (pos < lineEnd) {
                    while (data[pos] == ' ' || data[pos] == ';') pos++;
                    byte firstChar = (byte) (data[pos] | 0x20);
                    if (firstChar == 'n' && matchIgnoreCase(data, pos, lineEnd, NAME_EQ_BYTES)) {
                        pos += NAME_EQ_BYTES.length;
                        name = extractHeaderParamValue(data, pos, lineEnd);
                        pos = name.extra();
                    } else if (firstChar == 'f' && matchIgnoreCase(data, pos, lineEnd, FILENAME_EQ_BYTES)) {
                        pos += FILENAME_EQ_BYTES.length;
                        filename = extractHeaderParamValue(data, pos, lineEnd);
                        pos = filename.extra();
                    } else {
                        while (pos < lineEnd && data[pos] != ';') pos++;
                    }
                }
            } else if (matchIgnoreCase(data, i, lineEnd, CONTENT_TYPE_BYTES)) {
                int colonPos = i + CONTENT_TYPE_BYTES.length, valueEnd = lineEnd;
                while (data[colonPos] == ' ') colonPos++;
                while (valueEnd > colonPos && data[valueEnd - 1] == ' ') valueEnd--;
                contentType = HttpBuf.wrap(data, colonPos, valueEnd - colonPos);
            }

            if (lineEnd == end) break;
            i = lineEnd;
            if (data[i] == '\r') ++i;
            if (i < end && data[i] == '\n') ++i;
        }

        return new HttpBuf[]{name, filename, contentType};
    }

    /**
     * Extract parameter value from header (handles quoted and unquoted values).
     *
     * @param data  source data
     * @param start start position
     * @param end   end position (exclusive)
     * @return HttpBuf with value, extra() stores the new position after extraction
     */
    private static HttpBuf extractHeaderParamValue(byte[] data, int start, int end) {
        while (start < end && data[start] <= ' ') start++;
        if (start >= end) {
            return HttpBuf.empty().extra(start);
        }

        if (data[start] == '"') {
            int i = start + 1;
            byte c = 0;
            while (i < end && (c = data[i]) != '"' && c != '\\') ++i;

            if (c == '"') {
                return HttpBuf.wrap(data, start + 1, i - start - 1).extra(i + 1);
            }

            byte[] buffer = new byte[end - start];
            int bufLen = i - start - 1;
            System.arraycopy(data, start + 1, buffer, 0, bufLen);

            while (i < end && data[i] != '"') {
                if (data[i] == '\\' && i + 1 < end) {
                    buffer[bufLen++] = data[++i];
                } else {
                    buffer[bufLen++] = data[i];
                }
                ++i;
            }
            return HttpBuf.wrap(buffer, 0, bufLen).extra(i < end ? i + 1 : i);
        } else {
            int valueEnd = start;
            while (valueEnd < end && data[valueEnd] != ';') valueEnd++;
            while (valueEnd > start && data[valueEnd - 1] <= ' ') valueEnd--;
            return HttpBuf.wrap(data, start, valueEnd - start).extra(valueEnd);
        }
    }

    // ==================== SWAR optimized utilities ====================

    /**
     * Find the position of first line ending using SWAR optimization.
     * <p>
     * Returns position of '\r' for CRLF, or position of '\n' for standalone LF.
     * <p>
     * IMPORTANT: This method is guaranteed to find '\n' because callers ensure its existence
     * (e.g., when searching within a header block that ends with CRLFCRLF).
     * DO NOT call this method unless '\n' is known to exist in the range.
     *
     * @param data  byte array to search
     * @param start start position (inclusive)
     * @return position of '\r' for CRLF, otherwise position of '\n'
     */
    static int findNewlineGuaranteed(byte[] data, int start) {
        int i = start;
        while (true) {
            long mask = HttpUnsafe.maskOfNewline(HttpUnsafe.getLong(data, i));
            if (mask != 0) {
                int pos = i + HttpUnsafe.offsetTokenBytes(mask);
                if (data[pos] != '\n') {
                    // Negative bytes will also be hit, Usually encoded in UTF-8
                    // here we skip the continuous negative bytes
                    i = pos + 1;
                    while (data[i] < 0) ++i;
                    continue;
                }
                // pos > 0 is guaranteed by caller (multipart headers always start after boundary + CRLF)
                return data[pos - 1] == '\r' ? pos - 1 : pos;
            }
            i += 8;
        }
    }
}
