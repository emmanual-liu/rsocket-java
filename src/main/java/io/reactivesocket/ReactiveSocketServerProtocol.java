/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket;

import static rx.Observable.empty;
import static rx.Observable.error;
import static rx.Observable.just;
import static rx.RxReactiveStreams.toObservable;
import static rx.RxReactiveStreams.toPublisher;

import java.util.concurrent.ConcurrentHashMap;

import org.reactivestreams.Publisher;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;

/**
 * Server-side protocol implementation abstracted over a {@link DuplexConnection}.
 * <p>
 * Concrete implementations of {@link DuplexConnection} over TCP, WebSockets, Aeron, etc
 * can be passed to this class for protocol handling. The request handlers passed in at creation
 * will be invoked for each request over the connection.
 */
public class ReactiveSocketServerProtocol {

    // TODO only handle String right now
    private final RequestHandler requestHandler;

    private ReactiveSocketServerProtocol(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public static <T> ReactiveSocketServerProtocol create(RequestHandler requestHandler) {
        return new ReactiveSocketServerProtocol(requestHandler);
    }

    public Publisher<Void> acceptConnection(DuplexConnection ws) {
        // TODO consider using the LongObjectHashMap from Agrona for perf improvement
        // TODO consider alternate to PublishSubject that assumes a single subscriber and is lighter

        /* state of cancellation subjects during connection */
        final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables = new ConcurrentHashMap<>();
        /* streams in flight that can receive REQUEST_N messages */
        final ConcurrentHashMap<Integer, RequestOperator<?>> inFlight = new ConcurrentHashMap<>();

        return toPublisher(toObservable(ws.getInput()).flatMap(message -> {
            if (message.getMessageType() == MessageType.REQUEST_RESPONSE) {
                return handleRequestResponse(ws, message, cancellationObservables);
            } else if (message.getMessageType() == MessageType.REQUEST_STREAM) {
                return handleRequestStream(ws, message, cancellationObservables, inFlight);
            } else if (message.getMessageType() == MessageType.FIRE_AND_FORGET) {
                return handleFireAndForget(message);
            } else if (message.getMessageType() == MessageType.REQUEST_SUBSCRIPTION) {
                return handleRequestSubscription(ws, message, cancellationObservables, inFlight);
            } else if (message.getMessageType() == MessageType.CANCEL) {
                return handleCancellationRequest(cancellationObservables, message);
            } else if (message.getMessageType() == MessageType.REQUEST_N) {
                return handleRequestN(message, inFlight);
            } else {
                return error(new IllegalStateException("Unexpected prefix: " + message.getMessageType()));
            }
        }));
    }

    /*
     * Going to/from Publisher/Observable is really annoying.
     * 
     * This shows exactly why RxJava did not use an interface, only a concrete type.
     * 
     * Without extensions methods to Publisher, it always needs to be converted for use.
     * RxJava v2 will at least make it so we can return an Observable without converting back to Publisher.
     * 
     * TODO determine the performance and object allocation cost of all this conversion.
     * TODO explore if there is a better way of doing this while only exposing Publisher APIs
     */

    private Observable<Void> handleRequestResponse(DuplexConnection ws, Message requestMessage, final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables) {
        int streamId = requestMessage.getStreamId();
        CancellationToken cancellationToken = CancellationToken.create();
        cancellationObservables.put(requestMessage.getStreamId(), cancellationToken);

        return toObservable(ws.write(toPublisher(
                toObservable(requestHandler.handleRequestResponse(requestMessage.getMessage()))
                        .single()// enforce that it is a request/response
                        .flatMap(v -> just(
                                // TODO evaluate this ... as it is less efficient than a special NEXT_COMPLETE type
                                // TODO as a stream of 2 can not be as easily optimized like a scalar response
                                // NEXT with immediate COMPLETE as we have a single NEXT
                                Message.from(streamId, MessageType.NEXT, v),
                                Message.from(streamId, MessageType.COMPLETE, "")))
                        .onErrorReturn(err -> Message.from(streamId, MessageType.ERROR, err.getMessage()))
                        .takeUntil(cancellationToken)
                        .finallyDo(() -> cancellationObservables.remove(streamId)))));
    }

    private Observable<Void> handleRequestStream(DuplexConnection ws, Message message, final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables, ConcurrentHashMap<Integer, RequestOperator<?>> inflight) {
        return handleStream(ws, message,
                requestHandler::handleRequestStream,
                cancellationObservables, inflight,
                () -> just(Message.from(message.getStreamId(), MessageType.COMPLETE, "")));
    }

    private Observable<Void> handleRequestSubscription(DuplexConnection ws, Message message, final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables, ConcurrentHashMap<Integer, RequestOperator<?>> inflight) {
        return handleStream(ws, message,
                requestHandler::handleRequestSubscription,
                cancellationObservables, inflight,
                // we emit an error if the subscription completes as it is expected to be infinite
                () -> just(Message.from(message.getStreamId(), MessageType.ERROR, "Subscription terminated unexpectedly")));
    }

    /**
     * Common behavior between requestStream and requestSubscription
     * 
     * @param ws
     * @param message
     * @param cancellationObservables
     * @param inflight
     * @param onCompletedHandler
     * @return
     */
    private Observable<Void> handleStream(
            DuplexConnection ws,
            Message message,
            Func1<String, Publisher<String>> messageHandler,
            final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables,
            ConcurrentHashMap<Integer, RequestOperator<?>> inflight,
            Func0<? extends Observable<Message>> onCompletedHandler) {

        int streamId = message.getStreamId();
        CancellationToken cancellationToken = CancellationToken.create();
        cancellationObservables.put(streamId, cancellationToken);

        RequestOperator<String> requestor = new RequestOperator<String>();
        inflight.put(streamId, requestor);

        return toObservable(ws.write(toPublisher(
                toObservable(messageHandler.call(message.getMessage()))
                        // TODO pulling out requestN/backpressure for now as it's not working
                        //                                                .lift(requestor)
                        .flatMap(s -> just(Message.from(streamId, MessageType.NEXT, s)),
                                err -> just(Message.from(streamId, MessageType.ERROR, err.getMessage())),
                                onCompletedHandler)
                        .takeUntil(cancellationToken)
                        .finallyDo(() -> {
                            cancellationObservables.remove(streamId);
                            System.out.println("remove streamId");
                            inflight.remove(streamId);
                        }))));
    }

    /**
     * Fire-and-Forget so we invoke the handler and return nothing, not even errors.
     * 
     * @param ws
     * @param requestMessage
     * @return
     */
    private Observable<Void> handleFireAndForget(Message requestMessage) {
        return toObservable(requestHandler.handleFireAndForget(requestMessage.getMessage()))
                .onErrorResumeNext(error -> {
                    // swallow errors for fireAndForget ... no responses to client
                    // TODO add some kind of logging here
                    System.err.println("Server-side error for fireAndForget request: " + error);
                    return empty();
                });
    }

    private Observable<? extends Void> handleCancellationRequest(final ConcurrentHashMap<Integer, CancellationToken> cancellationObservables, Message message) {
        CancellationToken cancellationToken = cancellationObservables.get(message.getStreamId());
        if (cancellationToken != null) {
            cancellationToken.cancel();
        }
        return empty();
    }

    // TODO this needs further thought ... very prototypish implementation right now
    private Observable<? extends Void> handleRequestN(Message message, final ConcurrentHashMap<Integer, RequestOperator<?>> inFlight) {
        RequestOperator<?> requestor = inFlight.get(message.getStreamId());
        // TODO commented out as this isn't working yet
        //        System.out.println("*** requestN " + requestor);
        //        if (requestor == null || requestor.s == null) {
        //            // TODO need to figure out this race condition
        //            return error(new Exception("Not Yet Handled"));
        //        }
        //        requestor.s.requestMore(Long.parseLong(message.getMessage()));
        return empty();
    }

    private static class RequestOperator<T> implements Observable.Operator<T, T> {

        private RequestableSubscriber<T> s;

        @Override
        public Subscriber<? super T> call(Subscriber<? super T> child) {
            s = new RequestableSubscriber<T>(child);
            return s;
        }

    }

    private static final class RequestableSubscriber<T> extends Subscriber<T> {

        private final Subscriber<? super T> s;

        RequestableSubscriber(Subscriber<? super T> child) {
            this.s = child;
        }

        public void requestMore(long requested) {
            request(requested);
        }

        @Override
        public void onStart() {
            // TODO if we can get an initial request that would be better
            // starting "paused" for now until that is figured out
            request(0);
        }

        @Override
        public void onCompleted() {
            s.onCompleted();
        }

        @Override
        public void onError(Throwable e) {
            s.onError(e);
        }

        @Override
        public void onNext(T t) {
            s.onNext(t);
        }

    }
}
