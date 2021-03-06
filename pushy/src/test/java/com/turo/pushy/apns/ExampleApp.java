/*
 * Copyright (c) 2013-2017 Turo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.turo.pushy.apns;

import com.turo.pushy.apns.auth.ApnsSigningKey;
import com.turo.pushy.apns.proxy.Socks5ProxyHandlerFactory;
import com.turo.pushy.apns.util.ApnsPayloadBuilder;
import com.turo.pushy.apns.util.SimpleApnsPushNotification;
import com.turo.pushy.apns.util.TokenUtil;
import io.netty.util.concurrent.Future;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;

/**
 * This is a simple app that goes through the motions of creating an {@link ApnsClient}, connecting to the APNs
 * gateway, and sending a push notification. There's no expectation that this will actually work (we're referencing
 * credentials that don't actually exist, and the token/topic are crazy), but it should demonstrate all of the important
 * steps.
 *
 * @author <a href="https://github.com/jchambers">Jon Chambers</a>
 */
public class ExampleApp {

    public static void main(final String[] args) throws Exception {
        // The first thing to do is to create an APNs client. Clients need a
        // certificate and private key to authenticate with the APNs server. The
        // most common way to store the certificate and key is in a
        // password-protected PKCS#12 file.
        final ApnsClient apnsClient = new ApnsClientBuilder()
                .setSigningKey(ApnsSigningKey.loadFromPkcs8File(new File("/path/to/key.p8"),
                        "TEAMID1234", "KEYID67890"))
                .build();

        // Optional: we can listen for metrics by setting a metrics listener.
        apnsClient.setMetricsListener(new NoopMetricsListener());

        // Optional: we can set a proxy handler factory if we must use a proxy.
        apnsClient.setProxyHandlerFactory(
                new Socks5ProxyHandlerFactory(
                        new InetSocketAddress("my.proxy.com", 1080), "username", "password"));

        // Once we've created a client, we can connect it to the APNs gateway.
        // Note that this process is asynchronous; we'll get a Future right
        // away, but we'll need to wait for it to complete before we can send
        // any notifications. Note that this is a Netty Future, which is an
        // extension of the Java Future interface that allows callers to add
        // listeners and adds methods for checking the status of the Future.
        final Future<Void> connectFuture = apnsClient.connect(ApnsClient.DEVELOPMENT_APNS_HOST);
        connectFuture.await();

        // Once we're connected, we can start sending push notifications.
        final SimpleApnsPushNotification pushNotification;

        {
            final ApnsPayloadBuilder payloadBuilder = new ApnsPayloadBuilder();
            payloadBuilder.setAlertBody("Example!");

            final String payload = payloadBuilder.buildWithDefaultMaximumLength();
            final String token = TokenUtil.sanitizeTokenString("<efc7492 bdbd8209>");

            pushNotification = new SimpleApnsPushNotification(token, "com.example.myApp", payload);
        }

        // Like connecting, sending notifications is an asynchronous process.
        // We'll get a Future immediately, but will need to wait for the Future
        // to complete before we'll know whether the notification was accepted
        // or rejected by the APNs gateway.
        final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendNotificationFuture =
                apnsClient.sendNotification(pushNotification);

        try {
            final PushNotificationResponse<SimpleApnsPushNotification> pushNotificationResponse =
                    sendNotificationFuture.get();

            if (pushNotificationResponse.isAccepted()) {
                // Everything worked! The notification was successfully sent to
                // and accepted by the gateway.
            } else {
                // Something went wrong; this should be considered a permanent
                // failure, and we shouldn't try to send the notification again.
                System.out.println("Notification rejected by the APNs gateway: " +
                        pushNotificationResponse.getRejectionReason());

                if (pushNotificationResponse.getTokenInvalidationTimestamp() != null) {
                    // If we have an invalidation timestamp, we should also stop
                    // trying to send notifications to the destination token (unless
                    // it's been renewed somehow since the expiration timestamp).
                }
            }
        } catch (final ExecutionException e) {
            // Something went wrong when trying to send the notification to the
            // APNs gateway. The notification never actually reached the gateway,
            // so we shouldn't consider this a permanent failure.
            System.err.println("Failed to send push notification.");
            e.printStackTrace();

            if (e.getCause() instanceof ClientNotConnectedException) {
                // If we failed to send the notification because the client isn't
                // connected, we can wait for an automatic reconnection attempt
                // to succeed before sending more notifications.
                apnsClient.getReconnectionFuture().await();
            }
        }

        // Finally, when we're done sending notifications (i.e. when our
        // application is shutting down), we should disconnect all APNs clients
        // that may be in play.
        final Future<Void> disconnectFuture = apnsClient.disconnect();
        disconnectFuture.await();
    }
}
