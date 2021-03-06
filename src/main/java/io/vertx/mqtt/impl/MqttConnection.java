/*
 * Copyright 2016 Red Hat Inc.
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

package io.vertx.mqtt.impl;

import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.core.Handler;
import io.vertx.core.VertxException;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.core.spi.metrics.NetworkMetrics;
import io.vertx.core.spi.metrics.TCPMetrics;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;

import java.util.UUID;

/**
 * Represents an MQTT connection with a remote client
 */
public class MqttConnection extends ConnectionBase {

  // handler to call when a remote MQTT client connects and establishes a connection
  private Handler<MqttEndpoint> endpointHandler;

  // handler to call when an connection is rejected
  private Handler<Throwable> exceptionHandler;

  // endpoint for handling point-to-point communication with the remote MQTT client
  private MqttEndpointImpl endpoint;
  private final TCPMetrics metrics;

  private final MqttServerOptions options;

  @Override
  public NetworkMetrics metrics() {
    return metrics;
  }

  void init(Handler<MqttEndpoint> endpointHandler, Handler<Throwable> rejectHandler) {
    this.endpointHandler = endpointHandler;
    this.exceptionHandler = rejectHandler;
  }

  /**
   * Constructor
   *
   * @param vertx   Vert.x instance
   * @param channel Channel (netty) used for communication with MQTT remote client
   * @param context Vert.x context
   * @param metrics TCP metrics
   *
   */
  public MqttConnection(VertxInternal vertx, Channel channel, ContextImpl context,
                        TCPMetrics metrics, MqttServerOptions options) {
    super(vertx, channel, context);
    this.metrics = metrics;
    this.options = options;
  }

  @Override
  protected void handleInterestedOpsChanged() {
  }

  /**
   * Handle the MQTT message received by the remote MQTT client
   *
   * @param msg message to handle
   */
  synchronized void handleMessage(Object msg) {

    // handling directly native Netty MQTT messages because we don't need to
    // expose them at higher level (so no need for polyglotization)
    if (msg instanceof io.netty.handler.codec.mqtt.MqttMessage) {

      io.netty.handler.codec.mqtt.MqttMessage mqttMessage = (io.netty.handler.codec.mqtt.MqttMessage) msg;

      DecoderResult result = mqttMessage.decoderResult();
      if (result.isFailure()) {
        channel.pipeline().fireExceptionCaught(result.cause());
        return;
      }
      if (!result.isFinished()) {
        channel.pipeline().fireExceptionCaught(new Exception("Unfinished message"));
        return;
      }

      switch (mqttMessage.fixedHeader().messageType()) {

        case CONNECT:
          handleConnect((MqttConnectMessage) msg);
          break;

        case PUBACK:

          io.netty.handler.codec.mqtt.MqttPubAckMessage mqttPubackMessage = (io.netty.handler.codec.mqtt.MqttPubAckMessage) mqttMessage;
          this.handlePuback(mqttPubackMessage.variableHeader().messageId());
          break;

        case PUBREC:

          int pubrecMessageId = ((io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId();
          this.handlePubrec(pubrecMessageId);
          break;

        case PUBREL:

          int pubrelMessageId = ((io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId();
          this.handlePubrel(pubrelMessageId);
          break;

        case PUBCOMP:

          int pubcompMessageId = ((io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader) mqttMessage.variableHeader()).messageId();
          this.handlePubcomp(pubcompMessageId);
          break;

        case PINGREQ:

          this.handlePingreq();
          break;

        case DISCONNECT:

          this.handleDisconnect();
          break;

        default:

          this.channel.pipeline().fireExceptionCaught(new Exception("Wrong message type " + msg.getClass().getName()));
          break;

      }

      // handling mapped Vert.x MQTT messages (from Netty ones) because they'll be provided
      // to the higher layer (so need for ployglotization)
    } else {

      if (msg instanceof MqttSubscribeMessage) {

        this.handleSubscribe((MqttSubscribeMessage) msg);

      } else if (msg instanceof MqttUnsubscribeMessage) {

        this.handleUnsubscribe((MqttUnsubscribeMessage) msg);

      } else if (msg instanceof MqttPublishMessage) {

        this.handlePublish((MqttPublishMessage) msg);

      } else {

        this.channel.pipeline().fireExceptionCaught(new Exception("Wrong message type"));
      }
    }
  }

  /**
   * Used for calling the endpoint handler when a connection is established with a remote MQTT client
   */
  private void handleConnect(MqttConnectMessage msg) {

    // retrieve will information from CONNECT message
    MqttWillImpl will =
      new MqttWillImpl(msg.variableHeader().isWillFlag(),
        msg.payload().willTopic(),
        msg.payload().willMessage(),
        msg.variableHeader().willQos(),
        msg.variableHeader().isWillRetain());

    // retrieve authorization information from CONNECT message
    MqttAuthImpl auth = (msg.variableHeader().hasUserName() &&
      msg.variableHeader().hasPassword()) ?
      new MqttAuthImpl(
        msg.payload().userName(),
        msg.payload().password()) : null;

    // check if remote MQTT client didn't specify a client-id
    boolean isZeroBytes = (msg.payload().clientIdentifier() == null) ||
                          msg.payload().clientIdentifier().isEmpty();

    String clientIdentifier = null;

    // client-id got from payload or auto-generated (according to options)
    if (!isZeroBytes) {
      clientIdentifier = msg.payload().clientIdentifier();
    } else if (this.options.isAutoClientId()) {
      clientIdentifier = UUID.randomUUID().toString();
    }

    // create the MQTT endpoint provided to the application handler
    this.endpoint =
      new MqttEndpointImpl(
        this,
        clientIdentifier,
        auth,
        will,
        msg.variableHeader().isCleanSession(),
        msg.variableHeader().version(),
        msg.variableHeader().name(),
        msg.variableHeader().keepAliveTimeSeconds());

    // keep alive == 0 means NO keep alive, no timeout to handle
    if (msg.variableHeader().keepAliveTimeSeconds() != 0) {

      // the server waits for one and a half times the keep alive time period (MQTT spec)
      int timeout = msg.variableHeader().keepAliveTimeSeconds() +
        msg.variableHeader().keepAliveTimeSeconds() / 2;

      // modifying the channel pipeline for adding the idle state handler with previous timeout
      channel.pipeline().addBefore("handler", "idle", new IdleStateHandler(0, 0, timeout));
    }

    // MQTT spec 3.1.1 : if client-id is "zero-bytes", clean session MUST be true
    if (isZeroBytes && !msg.variableHeader().isCleanSession()) {
      this.endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED);
      if (this.exceptionHandler != null) {
        this.exceptionHandler.handle(new VertxException("With zero-length client-id, cleas session MUST be true"));
      }
    } else {

      // an exception at connection level is propagated to the endpoint
      this.exceptionHandler(t -> {
        this.endpoint.handleException(t);
      });

      this.endpointHandler.handle(this.endpoint);
    }
  }

  /**
   * Used for calling the subscribe handler when the remote MQTT client subscribes to topics
   *
   * @param msg message with subscribe information
   */
  synchronized void handleSubscribe(MqttSubscribeMessage msg) {

    if (this.checkConnected()) {
      this.endpoint.handleSubscribe(msg);
    }
  }

  /**
   * Used for calling the unsubscribe handler when the remote MQTT client unsubscribe to topics
   *
   * @param msg message with unsubscribe information
   */
  synchronized void handleUnsubscribe(MqttUnsubscribeMessage msg) {

    if (this.checkConnected()) {
      this.endpoint.handleUnsubscribe(msg);
    }
  }

  /**
   * Used for calling the publish handler when the remote MQTT client publishes a message
   *
   * @param msg published message
   */
  synchronized void handlePublish(MqttPublishMessage msg) {

    if (this.checkConnected()) {
      this.endpoint.handlePublish(msg);
    }
  }

  /**
   * Used for calling the puback handler when the remote MQTT client acknowledge a QoS 1 message with puback
   *
   * @param pubackMessageId identifier of the message acknowledged by the remote MQTT client
   */
  synchronized void handlePuback(int pubackMessageId) {

    if (this.checkConnected()) {
      this.endpoint.handlePuback(pubackMessageId);
    }
  }

  /**
   * Used for calling the pubrec handler when the remote MQTT client acknowledge a QoS 2 message with pubrec
   *
   * @param pubrecMessageId identifier of the message acknowledged by the remote MQTT client
   */
  synchronized void handlePubrec(int pubrecMessageId) {

    if (this.checkConnected()) {
      this.endpoint.handlePubrec(pubrecMessageId);
    }
  }

  /**
   * Used for calling the pubrel handler when the remote MQTT client acknowledge a QoS 2 message with pubrel
   *
   * @param pubrelMessageId identifier of the message acknowledged by the remote MQTT client
   */
  synchronized void handlePubrel(int pubrelMessageId) {

    if (this.checkConnected()) {
      this.endpoint.handlePubrel(pubrelMessageId);
    }
  }

  /**
   * Used for calling the pubcomp handler when the remote MQTT client acknowledge a QoS 2 message with pubcomp
   *
   * @param pubcompMessageId identifier of the message acknowledged by the remote MQTT client
   */
  synchronized void handlePubcomp(int pubcompMessageId) {

    if (this.checkConnected()) {
      this.endpoint.handlePubcomp(pubcompMessageId);
    }
  }

  /**
   * Used internally for handling the pinreq from the remote MQTT client
   */
  synchronized void handlePingreq() {

    if (this.checkConnected()) {
      this.endpoint.handlePingreq();
    }
  }

  /**
   * Used for calling the disconnect handler when the remote MQTT client disconnects
   */
  synchronized void handleDisconnect() {

    if (this.checkConnected()) {
      this.endpoint.handleDisconnect();
    }
  }

  /**
   * Used for calling the close handler when the remote MQTT client closes the connection
   */
  synchronized protected void handleClosed() {

    super.handleClosed();
    if (this.endpoint != null) {
      this.endpoint.handleClosed();
    }
  }

  /**
   * Check if the endpoint was created and is connected
   *
   * @return  status of the endpoint (connected or not)
   */
  private boolean checkConnected() {

    if ((this.endpoint != null) && (this.endpoint.isConnected())) {
      return true;
    } else {
      this.close();
      throw new IllegalStateException("Received an MQTT packet from a not connected client (CONNECT not sent yet)");
    }
  }
}
