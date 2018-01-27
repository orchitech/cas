package org.apereo.cas.web.support;

import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.audit.AuditTrailExecutionPlan;
import org.apereo.cas.util.DateTimeUtils;
import org.apereo.inspektr.audit.AuditActionContext;
import org.apereo.inspektr.common.web.ClientInfo;
import org.apereo.inspektr.common.web.ClientInfoHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Works in conjunction with the Inspektr Library to block attempts to dictionary attack users.
 * <p>
 * Defines a new Inspektr Action "THROTTLED_LOGIN_ATTEMPT" which keeps track of failed login attempts that don't result
 * in AUTHENTICATION_FAILED methods
 * <p>
 * This relies on the default Inspektr table layout and username construction.  The username construction can be overridden
 * in a subclass.
 *
 * @author Scott Battaglia
 * @since 3.3.5
 */
@Slf4j
public class InspektrThrottledSubmissionByIpAddressAndUsernameHandlerInterceptorAdapter extends AbstractThrottledSubmissionHandlerInterceptorAdapter {

    private static final double NUMBER_OF_MILLISECONDS_IN_SECOND = 1000.0;

    private static final String INSPEKTR_ACTION_THROTTLED = "THROTTLED_LOGIN_ATTEMPT";

    private final AuditTrailExecutionPlan auditTrailManager;
    private final DataSource dataSource;
    private final String applicationCode;
    private final String authenticationFailureCode;
    private final String sqlQueryAudit;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Instantiates a new inspektr throttled submission by ip address and username handler interceptor adapter.
     *
     * @param failureThreshold          the failure threshold
     * @param failureRangeInSeconds     the failure range in seconds
     * @param usernameParameter         the username parameter
     * @param auditTrailManager         the audit trail manager
     * @param dataSource                the data source
     * @param appCode                   the app code
     * @param sqlQueryAudit             the sql query audit
     * @param authenticationFailureCode the authentication failure code
     */
    public InspektrThrottledSubmissionByIpAddressAndUsernameHandlerInterceptorAdapter(final int failureThreshold,
                                                                                      final int failureRangeInSeconds,
                                                                                      final String usernameParameter,
                                                                                      final AuditTrailExecutionPlan auditTrailManager,
                                                                                      final DataSource dataSource, final String appCode,
                                                                                      final String sqlQueryAudit, final String authenticationFailureCode) {
        super(failureThreshold, failureRangeInSeconds, usernameParameter);
        this.auditTrailManager = auditTrailManager;
        this.dataSource = dataSource;
        this.applicationCode = appCode;
        this.sqlQueryAudit = sqlQueryAudit;
        this.authenticationFailureCode = authenticationFailureCode;
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
    }

    @Override
    public boolean exceedsThreshold(final HttpServletRequest request) {
        final String userToUse = constructUsername(request, getUsernameParameter());
        final ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(getFailureRangeInSeconds());

        final ClientInfo clientInfo = ClientInfoHolder.getClientInfo();
        final String remoteAddress = clientInfo.getClientIpAddress();

        final List<Timestamp> failures = this.jdbcTemplate.query(
            this.sqlQueryAudit,
            new Object[]{
                remoteAddress, userToUse, this.authenticationFailureCode,
                this.applicationCode, DateTimeUtils.timestampOf(cutoff)},
            new int[]{Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP},
            (resultSet, i) -> resultSet.getTimestamp(1));
        if (failures.size() < 2) {
            return false;
        }
        // Compute rate in submissions/sec between last two authn failures and compare with threshold
        final long lastTime = failures.get(0).getTime();
        final long secondToLastTime = failures.get(1).getTime();
        final long difference = lastTime - secondToLastTime;
        final double rate = NUMBER_OF_MILLISECONDS_IN_SECOND / difference;
        LOGGER.debug("Last attempt was at [{}] and the one before that was at [{}]. Difference is [{}] calculated as rate of [{}]",
            lastTime, secondToLastTime, difference, rate);
        if (rate > getThresholdRate()) {
            LOGGER.warn("Authentication throttling rate [{}] exceeds the defined threshold [{}]", rate, getThresholdRate());
            return true;
        }
        return false;
    }

    @Override
    public void recordSubmissionFailure(final HttpServletRequest request) {
        super.recordSubmissionFailure(request);
        recordAnyAction(request, this.authenticationFailureCode, "recordSubmissionFailure()");
    }

    @Override
    protected void recordThrottle(final HttpServletRequest request) {
        super.recordThrottle(request);
        recordAnyAction(request, INSPEKTR_ACTION_THROTTLED, "recordThrottle()");
    }

    /**
     * Records an audit action.
     *
     * @param request    The current HTTP request.
     * @param actionName Name of the action to be recorded.
     * @param methodName Name of the method where the action occurred.
     */
    protected void recordAnyAction(final HttpServletRequest request, final String actionName, final String methodName) {
        final String userToUse = constructUsername(request, getUsernameParameter());
        final ClientInfo clientInfo = ClientInfoHolder.getClientInfo();
        final AuditActionContext context = new AuditActionContext(
            userToUse,
            userToUse,
            actionName,
            this.applicationCode,
            DateTimeUtils.dateOf(ZonedDateTime.now(ZoneOffset.UTC)),
            clientInfo.getClientIpAddress(),
            clientInfo.getServerIpAddress());
        this.auditTrailManager.record(context);
    }

    /**
     * Construct username from the request.
     *
     * @param request           the request
     * @param usernameParameter the username parameter
     * @return the string
     */
    private static String constructUsername(final HttpServletRequest request, final String usernameParameter) {
        return request.getParameter(usernameParameter);
    }

    @Override
    public String getName() {
        return "inspektrIpAddressUsernameThrottle";
    }
}
