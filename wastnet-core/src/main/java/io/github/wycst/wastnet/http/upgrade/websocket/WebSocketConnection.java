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
package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

/**
 * WebSocket connection interface
 * Provides core operation methods for WebSocket connections
 *
 * @since 2024-1-27
 * @author wangyc
 */
public interface WebSocketConnection {

    /**
     * Send text message
     *
     * @param message text message content
     * @throws IOException throws IO exception when sending fails
     */
    void sendText(String message) throws IOException;

    /**
     * Send binary data
     *
     * @param data binary data
     * @throws IOException throws IO exception when sending fails
     */
    void sendBinary(byte[] data) throws IOException;

    /**
     * Send a file as a binary WebSocket stream. Large files are automatically
     * split into continuation frames without loading the entire file into memory.
     *
     * @param file the file to send
     * @throws IOException throws IO exception when sending fails
     */
    void sendFile(File file) throws IOException;

    /**
     * Send data from an InputStream as a binary WebSocket stream. Data is read
     * and sent chunk-by-chunk using continuation frames, suitable for large
     * payloads where loading everything into memory is undesirable.
     *
     * @param in the input stream to read from
     * @throws IOException throws IO exception when sending fails
     */
    void sendInputStream(InputStream in) throws IOException;

    /**
     * Close WebSocket connection
     *
     * @param code   close code
     * @param reason close reason
     * @throws IOException throws IO exception when closing fails
     */
    void close(int code, String reason) throws IOException;

    /**
     * Check if connection is closed
     *
     * @return true if closed, false if still active
     */
    boolean isClosed();

    /**
     * Get associated HTTP request
     *
     * @return original HTTP upgrade request
     */
    HttpRequest request();

    /**
     * Get subprotocol
     *
     * @return subprotocol string
     */
    String subprotocol();

    /**
     * Connection ID
     *
     * @return connection ID
     */
    String id();

    /**
     * set account
     *
     * @param account account info
     */
    void setAccount(Serializable account);

    /**
     * get account
     *
     * @return account info
     */
    Serializable getAccount();

    /**
     * set groupId
     *
     * @param groupId groupId
     */
    void setGroupId(Serializable groupId);

    /**
     * get groupId
     *
     * @return groupId
     */
    Serializable getGroupId();

    /**
     * Disconnect WebSocket connection
     */
    void disconnect();

    /**
     * Push WebSocket frame
     *
     * @param frame WebSocket frame to push
     */
    void push(WebSocketFrame frame);

    /**
     * Ping
     */
    void ping() throws IOException;

    /**
     * Pong
     */
    void pong() throws IOException;

    /**
     * Update connection's last active time
     * For internal use only, called when receiving client data
     */
    void updateActiveTime();

    /**
     * Initialize connection timeout detection mechanism
     *
     * @param timeout  Timeout time in seconds
     * @param strategy Timeout handling strategy
     */
    void timeoutDetection(int timeout, WebSocketResource.TimeoutStrategy strategy);

}
