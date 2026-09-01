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

import io.github.wycst.wastnet.env.RuntimeEnv;

import java.util.*;

/**
 * HTTP body decoder implementation based on byte array.
 * Decodes body data from byte array.
 *
 * @author wangyc
 * @since 2024/3/4
 */
final class HttpBodyDefaultDecoder extends HttpBodyDecoder {
    private final byte[] bodyData;
    private final int bodyLength;
    private String bodyString;

    HttpBodyDefaultDecoder(String contentType, byte[] bodyData) {
        super(contentType);
        this.bodyData = bodyData;
        this.bodyLength = bodyData != null ? bodyData.length : 0;
        if (RuntimeEnv.JDK_VERSION >= 9) {
            bodyString = bodyData == null ? "" : HttpUnsafe.createAsciiString(bodyData);
        }
    }

    @Override
    protected void doDecodeMultipartFields(byte[] boundaryBytes) {
        if (bodyLength == 0) {
            multipartFields = Collections.emptyMap();
            return;
        }
        final String bodyDataStr = this.bodyString;
        final boolean useAsciiStringApi = bodyDataStr != null;
        final String boundaryStr = useAsciiStringApi ? HttpUnsafe.createAsciiString(boundaryBytes) : null;
        // Pre-build badChar table for searching next boundary in content
        final int[] badChar = useAsciiStringApi ? null : buildBadCharTable(boundaryBytes);
        int boundaryLen = boundaryBytes.length;

        Map<String, List<MultipartField>> result = new HashMap<>(8);
        int offset = 0;
        int fieldIndex = 0;

        try {
            while (offset < bodyLength) {
                // Only the first iteration needs to verify boundary prefix (at position 0)
                // Subsequent iterations use findBytes which already determines the position
                if (offset == 0 && (useAsciiStringApi ? !bodyDataStr.startsWith(boundaryStr) : !startsWithBoundary(bodyData, boundaryBytes)))
                    break;

                int partStart = offset + boundaryLen;
                // No bounds check here - if out of bounds, it indicates malformed data (caught by try-catch)
                if (bodyData[partStart] == '\r' && bodyData[partStart + 1] == '\n') {
                    partStart += 2;
                } else {
                    // End marker (--boundary--) or format error
                    break;
                }

                int headerEnd = useAsciiStringApi ? bodyDataStr.indexOf(CRLFCRLF, partStart) : HttpUnsafe.findCRLFCRLF(bodyData, partStart, bodyLength);
                if (headerEnd == -1) break;

                int contentStart = headerEnd + 4;
                if (contentStart > bodyLength) break;

                int nextBoundary = useAsciiStringApi ? bodyDataStr.indexOf(boundaryStr, contentStart) : findBytes(bodyData, contentStart, bodyLength, boundaryBytes, badChar);
                if (nextBoundary == -1) break;

                // Remove \r\n before boundary
                int contentEnd = nextBoundary;
                if (contentEnd > contentStart && bodyData[contentEnd - 1] == '\n') {
                    --contentEnd;
                    if (contentEnd > contentStart && bodyData[contentEnd - 1] == '\r') {
                        --contentEnd;
                    }
                }

                // Parse all multipart headers in one pass: name, filename, content-type
                HttpBuf[] headerValues = parseMultipartHeaders(bodyData, partStart, headerEnd);
                String fieldName = headerValues[0] != null ? headerValues[0].toString(charset) : null;
                if (fieldName == null || fieldName.isEmpty()) {
                    offset = nextBoundary;
                    continue;
                }
                MultipartField field = new MultipartFieldData(fieldName, headerValues[1], headerValues[2], HttpBuf.wrap(bodyData, contentStart, contentEnd - contentStart), charset);
                field.setIndex(fieldIndex++);
                List<MultipartField> fieldList = result.computeIfAbsent(fieldName, k -> new ArrayList<>(1));
                fieldList.add(field);

                offset = nextBoundary;
            }
        } catch (Exception e) {
            // ignore
        }

        multipartFields = result;
    }

    @Override
    protected void decodeFormUrlencoded() {
        if (!isFormUrlencoded() || bodyLength == 0) {
            urlencodedParams = Collections.emptyMap();
            return;
        }

        try {
            HttpUriDecoder decoder = new HttpUriDecoder(false, true);
            decoder.codec(bodyData).endCodec();
            urlencodedParams = decoder.getParameters();
        } catch (Exception e) {
            urlencodedParams = Collections.emptyMap();
        }
    }

    @Override
    void release() {
        super.release();
        bodyString = null;
    }
}
