package com.woobeee.game.result;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Readable;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.r2dbc.core.StatementFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Hand-written {@link DatabaseClient} test double for {@link GameResultRepositoryTest}.
 *
 * <p>Mockito's {@code RETURNS_DEEP_STUBS} cannot faithfully model this fluent API once more than
 * one {@code .bind(...)} call is chained before the terminal call. {@code GameResultRepository}
 * chains up to six {@code .bind(...)} calls before {@code .map(...).one()} /
 * {@code .fetch().rowsUpdated()}. A deep-stub {@code when(...)} recording covers exactly one
 * concrete invocation chain; every additional, unstubbed {@code .bind(...)} call in between
 * returns a brand-new, entirely unstubbed child mock. The terminal call then lands on a mock
 * {@code Mono}/{@code Flux} whose {@code subscribe(Subscriber)} is a no-op (it is an unstubbed
 * void interface method), so a real subscriber — including {@code StepVerifier} — never receives
 * {@code onSubscribe}/{@code onNext}/{@code onComplete} and waits forever. That is what hung the
 * suite: {@code insertResultReturnsTheGeneratedId} and {@code attachReplayKeyUpdatesTheRow} never
 * even reached "subscribed", and {@code insertParticipantsBindsGuestMemberIdAsNull} hung inside
 * {@code concatMap} waiting on an inner publisher that never signals.
 *
 * <p>This class replaces the deep stub with a real (if minimal) implementation: each call to
 * {@link #sql(String)} records a {@link Statement} that captures the exact SQL text and every
 * bound parameter, tracking separately which ones were bound via {@code bind(name, value)} versus
 * {@code bindNull(name, type)}. Canned results are queued per exact SQL text (FIFO, since the same
 * INSERT is executed once per participant) and returned through real {@code Mono}/{@code Flux}
 * instances, so normal Reactor subscription semantics apply and {@code StepVerifier} works
 * correctly without any mocking framework involved.
 */
final class RecordingDatabaseClient implements DatabaseClient {

    private final List<Statement> executed = new ArrayList<>();
    private final Map<String, Deque<Object>> oneResults = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> rowsUpdatedResults = new LinkedHashMap<>();

    void stubOne(String sql, Object result) {
        oneResults.computeIfAbsent(sql, key -> new ArrayDeque<>()).add(result);
    }

    void stubRowsUpdated(String sql, long result) {
        rowsUpdatedResults.computeIfAbsent(sql, key -> new ArrayDeque<>()).add(result);
    }

    List<Statement> executedStatements() {
        return executed;
    }

    Statement onlyStatement() {
        if (executed.size() != 1) {
            throw new IllegalStateException("expected exactly one executed statement, got " + executed.size());
        }
        return executed.get(0);
    }

    @Override
    public GenericExecuteSpec sql(String sql) {
        Statement statement = new Statement(sql);
        executed.add(statement);
        return statement;
    }

    @Override
    public GenericExecuteSpec sql(Supplier<String> sqlSupplier) {
        return sql(sqlSupplier.get());
    }

    @Override
    public ConnectionFactory getConnectionFactory() {
        throw new UnsupportedOperationException("not needed by GameResultRepository");
    }

    @Override
    public <T> Mono<T> inConnection(Function<Connection, Mono<T>> action) {
        throw new UnsupportedOperationException("not needed by GameResultRepository");
    }

    @Override
    public <T> Flux<T> inConnectionMany(Function<Connection, Flux<T>> action) {
        throw new UnsupportedOperationException("not needed by GameResultRepository");
    }

    /** One executed {@code .sql(...)} call, with every bound / bind-nulled parameter captured, in bind order. */
    final class Statement implements GenericExecuteSpec {
        private final String sql;
        private final Map<String, Object> bound = new LinkedHashMap<>();
        private final Map<String, Class<?>> boundNull = new LinkedHashMap<>();

        private Statement(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }

        /** Parameters bound via {@code bind(name, value)}. Never contains a key also in {@link #boundNull()}. */
        Map<String, Object> bound() {
            return bound;
        }

        /** Parameters bound via {@code bindNull(name, type)}. Never contains a key also in {@link #bound()}. */
        Map<String, Class<?>> boundNull() {
            return boundNull;
        }

        @Override
        public GenericExecuteSpec bind(String name, Object value) {
            bound.put(name, value);
            return this;
        }

        @Override
        public GenericExecuteSpec bindNull(String name, Class<?> type) {
            boundNull.put(name, type);
            return this;
        }

        @Override
        public GenericExecuteSpec bind(int index, Object value) {
            throw new UnsupportedOperationException("GameResultRepository binds by name, not index");
        }

        @Override
        public GenericExecuteSpec bindNull(int index, Class<?> type) {
            throw new UnsupportedOperationException("GameResultRepository binds by name, not index");
        }

        @Override
        public GenericExecuteSpec bindValues(List<?> values) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public GenericExecuteSpec bindValues(Map<String, ?> values) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public GenericExecuteSpec bindProperties(Object bean) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public GenericExecuteSpec filter(StatementFilterFunction filter) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> RowsFetchSpec<R> map(Function<? super Readable, R> mappingFunction) {
            Deque<Object> queue = oneResults.get(sql);
            Object result = (queue == null || queue.isEmpty()) ? null : queue.poll();
            return new RowsFetchSpec<R>() {
                @Override
                public Mono<R> one() {
                    if (result == null) {
                        return Mono.error(new IllegalStateException("no stubbed .one() result for SQL: " + sql));
                    }
                    return Mono.just((R) result);
                }

                @Override
                public Mono<R> first() {
                    throw new UnsupportedOperationException("not used by GameResultRepository");
                }

                @Override
                public Flux<R> all() {
                    throw new UnsupportedOperationException("not used by GameResultRepository");
                }
            };
        }

        @Override
        public <R> RowsFetchSpec<R> map(BiFunction<Row, RowMetadata, R> mappingFunction) {
            throw new UnsupportedOperationException("GameResultRepository maps via Readable, not Row/RowMetadata");
        }

        @Override
        public <R> RowsFetchSpec<R> mapValue(Class<R> mappedClass) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public <R> RowsFetchSpec<R> mapProperties(Class<R> mappedClass) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public <R> Flux<R> flatMap(Function<Result, Publisher<R>> fetchFunction) {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }

        @Override
        public FetchSpec<Map<String, Object>> fetch() {
            Deque<Long> queue = rowsUpdatedResults.get(sql);
            Long result = (queue == null || queue.isEmpty()) ? null : queue.poll();
            return new FetchSpec<Map<String, Object>>() {
                @Override
                public Mono<Long> rowsUpdated() {
                    if (result == null) {
                        return Mono.error(new IllegalStateException("no stubbed rowsUpdated() result for SQL: " + sql));
                    }
                    return Mono.just(result);
                }

                @Override
                public Mono<Map<String, Object>> one() {
                    throw new UnsupportedOperationException("not used by GameResultRepository");
                }

                @Override
                public Mono<Map<String, Object>> first() {
                    throw new UnsupportedOperationException("not used by GameResultRepository");
                }

                @Override
                public Flux<Map<String, Object>> all() {
                    throw new UnsupportedOperationException("not used by GameResultRepository");
                }
            };
        }

        @Override
        public Mono<Void> then() {
            throw new UnsupportedOperationException("not used by GameResultRepository");
        }
    }
}
