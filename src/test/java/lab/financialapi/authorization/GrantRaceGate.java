package lab.financialapi.authorization;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트 전용. 실제 SQL 실행 후 반환 경계만 멈추며 DB 잠금·스냅샷 동작은 대체하지 않는다. */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
final class GrantRaceGate implements Interceptor {
    private final JdbcTemplate jdbc;
    private volatile String firstStatement;
    private volatile String secondStatement;
    private volatile CountDownLatch firstExecuted = new CountDownLatch(1);
    private volatile CountDownLatch secondStarted = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    volatile int firstPid;
    volatile int secondPid;

    GrantRaceGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void arm(String first, String second) {
        firstStatement = first;
        secondStatement = second;
        firstExecuted = new CountDownLatch(1);
        secondStarted = new CountDownLatch(1);
        release = new CountDownLatch(1);
        firstPid = 0;
        secondPid = 0;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
        String thread = Thread.currentThread().getName();
        boolean first = thread.equals("grant-race-first") && statement.endsWith(firstStatement);
        boolean second = thread.equals("grant-race-second") && statement.endsWith(secondStatement);
        if (first) {
            firstPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        }
        if (second) {
            secondPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            secondStarted.countDown();
        }
        Object result = invocation.proceed();
        if (first) {
            firstExecuted.countDown();
            await(release);
        }
        return result;
    }

    void awaitFirst() throws InterruptedException {
        await(firstExecuted);
    }

    void awaitSecond() throws InterruptedException {
        await(secondStarted);
    }

    void release() {
        release.countDown();
    }

    private void await(CountDownLatch signal) throws InterruptedException {
        if (!signal.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("SQL 경합 동기화 신호 시간 초과");
        }
    }
}
