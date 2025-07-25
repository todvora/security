/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.auditlog.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.ExceptionsHelper;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.xcontent.MediaType;
import org.opensearch.rest.RestRequest;
import org.opensearch.security.auditlog.AuditLog.Operation;
import org.opensearch.security.auditlog.AuditLog.Origin;
import org.opensearch.security.auditlog.config.AuditConfig;
import org.opensearch.security.dlic.rest.support.Utils;
import org.opensearch.security.filter.OpenSearchRequest;
import org.opensearch.security.filter.SecurityRequest;
import org.opensearch.security.securityconf.impl.CType;
import org.opensearch.security.support.WildcardMatcher;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import static org.opensearch.security.OpenSearchSecurityPlugin.LEGACY_OPENDISTRO_PREFIX;
import static org.opensearch.security.OpenSearchSecurityPlugin.PLUGINS_PREFIX;

public final class AuditMessage {

    private static final Logger log = LogManager.getLogger(AuditMessage.class);

    // clustername and cluster uuid
    private static final WildcardMatcher AUTHORIZATION_HEADER = WildcardMatcher.from("Authorization").ignoreCase();
    private static final String SENSITIVE_KEY = "password";
    private static final String SENSITIVE_REPLACEMENT_VALUE = "__SENSITIVE__";

    private static final Pattern SENSITIVE_PATHS = Pattern.compile(
        "/(" + LEGACY_OPENDISTRO_PREFIX + "|" + PLUGINS_PREFIX + ")/api/(account.*|internalusers.*|user.*)"
    );

    @VisibleForTesting
    public static final String BCRYPT_REGEX = "\\$2[ayb]\\$.{56}";
    public static final String PBKDF2_REGEX = "\\$\\d+\\$\\d+\\$[A-Za-z0-9+/]+={0,2}\\$[A-Za-z0-9+/]+={0,2}";
    public static final String ARGON2_REGEX =
        "\\$argon2(?:id|i|d)\\$v=\\d+\\$(?:[a-z]=\\d+,?)+\\$[A-Za-z0-9+/]+={0,2}\\$[A-Za-z0-9+/]+={0,2}";
    public static final Pattern HASH_REGEX_PATTERN = Pattern.compile(BCRYPT_REGEX + "|" + PBKDF2_REGEX + "|" + ARGON2_REGEX);
    private static final String HASH_REPLACEMENT_VALUE = "__HASH__";
    private static final String INTERNALUSERS_DOC_ID = CType.INTERNALUSERS.toLCString();

    public static final String FORMAT_VERSION = "audit_format_version";
    public static final String CATEGORY = "audit_category";
    public static final String REQUEST_EFFECTIVE_USER = "audit_request_effective_user";
    public static final String REQUEST_INITIATING_USER = "audit_request_initiating_user";
    public static final String UTC_TIMESTAMP = "@timestamp";

    public static final String CLUSTER_NAME = "audit_cluster_name";
    public static final String NODE_ID = "audit_node_id";
    public static final String NODE_HOST_ADDRESS = "audit_node_host_address";
    public static final String NODE_HOST_NAME = "audit_node_host_name";
    public static final String NODE_NAME = "audit_node_name";

    public static final String ORIGIN = "audit_request_origin";
    public static final String REMOTE_ADDRESS = "audit_request_remote_address";

    public static final String REST_REQUEST_PATH = "audit_rest_request_path";
    public static final String REST_REQUEST_PARAMS = "audit_rest_request_params";
    public static final String REST_REQUEST_HEADERS = "audit_rest_request_headers";
    public static final String REST_REQUEST_METHOD = "audit_rest_request_method";

    public static final String TRANSPORT_REQUEST_TYPE = "audit_transport_request_type";
    public static final String TRANSPORT_ACTION = "audit_transport_action";
    public static final String TRANSPORT_REQUEST_HEADERS = "audit_transport_headers";

    public static final String ID = "audit_trace_doc_id";
    // public static final String TYPES = "audit_trace_doc_types";
    // public static final String SOURCE = "audit_trace_doc_source";
    public static final String INDICES = "audit_trace_indices";
    public static final String SHARD_ID = "audit_trace_shard_id";
    public static final String RESOLVED_INDICES = "audit_trace_resolved_indices";

    public static final String EXCEPTION = "audit_request_exception_stacktrace";
    public static final String IS_ADMIN_DN = "audit_request_effective_user_is_admin";
    public static final String PRIVILEGE = "audit_request_privilege";

    public static final String TASK_ID = "audit_trace_task_id";
    public static final String TASK_PARENT_ID = "audit_trace_task_parent_id";

    public static final String REQUEST_BODY = "audit_request_body";
    public static final String COMPLIANCE_DIFF_IS_NOOP = "audit_compliance_diff_is_noop";
    public static final String COMPLIANCE_DIFF_CONTENT = "audit_compliance_diff_content";
    public static final String COMPLIANCE_FILE_INFOS = "audit_compliance_file_infos";

    // public static final String COMPLIANCE_DIFF_STORED_IS_NOOP = "audit_compliance_diff_stored_is_noop";
    // public static final String COMPLIANCE_STORED_FIELDS_CONTENT = "audit_compliance_stored_fields_content";

    public static final String REQUEST_LAYER = "audit_request_layer";

    public static final String COMPLIANCE_OPERATION = "audit_compliance_operation";
    public static final String COMPLIANCE_DOC_VERSION = "audit_compliance_doc_version";

    private static final DateTimeFormatter DEFAULT_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
    private final Map<String, Object> auditInfo = new HashMap<String, Object>(50);
    private final AuditCategory msgCategory;

    public AuditMessage(final AuditCategory msgCategory, final ClusterService clusterService, final Origin origin, final Origin layer) {
        this.msgCategory = Objects.requireNonNull(msgCategory);
        final String currentTime = currentTime();
        auditInfo.put(FORMAT_VERSION, 4);
        auditInfo.put(CATEGORY, Objects.requireNonNull(msgCategory));
        auditInfo.put(UTC_TIMESTAMP, currentTime);
        auditInfo.put(NODE_HOST_ADDRESS, Objects.requireNonNull(clusterService).localNode().getHostAddress());
        auditInfo.put(NODE_ID, Objects.requireNonNull(clusterService).localNode().getId());
        auditInfo.put(NODE_HOST_NAME, Objects.requireNonNull(clusterService).localNode().getHostName());
        auditInfo.put(NODE_NAME, Objects.requireNonNull(clusterService).localNode().getName());
        auditInfo.put(CLUSTER_NAME, Objects.requireNonNull(clusterService).getClusterName().value());

        if (origin != null) {
            auditInfo.put(ORIGIN, origin);
        }

        if (layer != null) {
            auditInfo.put(REQUEST_LAYER, layer);
        }
    }

    public void addRemoteAddress(TransportAddress remoteAddress) {
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            auditInfo.put(REMOTE_ADDRESS, remoteAddress.getAddress());
        }
    }

    public void addIsAdminDn(boolean isAdminDn) {
        auditInfo.put(IS_ADMIN_DN, isAdminDn);
    }

    public void addException(Throwable t) {
        if (t != null) {
            auditInfo.put(EXCEPTION, ExceptionsHelper.stackTrace(t));
        }
    }

    public void addPrivilege(String priv) {
        if (priv != null) {
            auditInfo.put(PRIVILEGE, priv);
        }
    }

    public void addInitiatingUser(String user) {
        if (user != null) {
            auditInfo.put(REQUEST_INITIATING_USER, user);
        }
    }

    public void addEffectiveUser(String user) {
        if (user != null) {
            auditInfo.put(REQUEST_EFFECTIVE_USER, user);
        }
    }

    public void addPath(String path) {
        if (path != null) {
            auditInfo.put(REST_REQUEST_PATH, path);
        }
    }

    public void addComplianceWriteDiffSource(String diff) {
        if (diff != null && !diff.isEmpty()) {
            auditInfo.put(COMPLIANCE_DIFF_CONTENT, diff);
            auditInfo.put(COMPLIANCE_DIFF_IS_NOOP, false);
        } else if (diff != null && diff.isEmpty()) {
            auditInfo.put(COMPLIANCE_DIFF_IS_NOOP, true);
        }
    }

    void addSecurityConfigWriteDiffSource(final String diff, final String id) {
        addComplianceWriteDiffSource(redactSecurityConfigContent(diff, id));
    }

    // public void addComplianceWriteStoredFields0(String diff) {
    // if (diff != null && !diff.isEmpty()) {
    // auditInfo.put(COMPLIANCE_STORED_FIELDS_CONTENT, diff);
    // //auditInfo.put(COMPLIANCE_DIFF_STORED_IS_NOOP, false);
    // }
    // }

    public void addTupleToRequestBody(Tuple<MediaType, BytesReference> xContentTuple) {
        if (xContentTuple != null) {
            try {
                auditInfo.put(REQUEST_BODY, XContentHelper.convertToJson(xContentTuple.v2(), false, xContentTuple.v1()));
            } catch (Exception e) {
                auditInfo.put(REQUEST_BODY, "ERROR: Unable to convert to json because of " + e.toString());
            }
        }
    }

    public void addMapToRequestBody(Map<String, ?> map) {
        if (map != null) {
            auditInfo.put(REQUEST_BODY, Utils.convertStructuredMapToJson(map));
        }
    }

    public void addUnescapedJsonToRequestBody(String source) {
        if (source != null) {
            auditInfo.put(REQUEST_BODY, source);
        }
    }

    private String redactSecurityConfigContent(String content, String id) {
        if (content != null && INTERNALUSERS_DOC_ID.equals(id)) {
            content = HASH_REGEX_PATTERN.matcher(content).replaceAll(HASH_REPLACEMENT_VALUE);
        }
        return content;
    }

    void addSecurityConfigContentToRequestBody(final String source, final String id) {
        if (source != null) {
            final String redactedContent = redactSecurityConfigContent(source, id);
            auditInfo.put(REQUEST_BODY, redactedContent);
        }
    }

    void addSecurityConfigTupleToRequestBody(final Tuple<XContentType, BytesReference> xContentTuple, final String id) {
        if (xContentTuple != null) {
            try {
                addSecurityConfigContentToRequestBody(XContentHelper.convertToJson(xContentTuple.v2(), false, xContentTuple.v1()), id);
            } catch (Exception e) {
                auditInfo.put(REQUEST_BODY, "ERROR: Unable to convert to json");
            }
        }
    }

    void addSecurityConfigMapToRequestBody(final Map<String, ?> map, final String id) {
        if (map != null) {
            addSecurityConfigContentToRequestBody(Utils.convertStructuredMapToJson(map), id);
        }
    }

    public void addRequestType(String requestType) {
        if (requestType != null) {
            auditInfo.put(TRANSPORT_REQUEST_TYPE, requestType);
        }
    }

    public void addAction(String action) {
        if (action != null) {
            auditInfo.put(TRANSPORT_ACTION, action);
        }
    }

    public void addId(String id) {
        if (id != null) {
            auditInfo.put(ID, id);
        }
    }

    /*public void addTypes(String[] types) {
        if (types != null && types.length > 0) {
            auditInfo.put(TYPES, types);
        }
    }

    public void addType(String type) {
        if (type != null) {
            auditInfo.put(TYPES, new String[] { type });
        }
    }*/

    public void addFileInfos(Map<String, Path> paths) {
        if (paths != null && !paths.isEmpty()) {
            List<Object> infos = new ArrayList<>();
            for (Entry<String, Path> path : paths.entrySet()) {

                try {
                    if (Files.isReadable(path.getValue())) {
                        final String chcksm = DigestUtils.sha256Hex(Files.readAllBytes(path.getValue()));
                        FileTime lm = Files.getLastModifiedTime(path.getValue(), LinkOption.NOFOLLOW_LINKS);
                        Map<String, Object> innerInfos = new HashMap<>();
                        innerInfos.put("sha256", chcksm);
                        innerInfos.put("last_modified", formatTime(lm.toMillis()));
                        innerInfos.put("key", path.getKey());
                        innerInfos.put("path", path.getValue().toAbsolutePath().toString());
                        infos.add(innerInfos);
                    }
                } catch (Throwable e) {
                    // ignore non readable files
                }
            }
            auditInfo.put(COMPLIANCE_FILE_INFOS, infos);
        }
    }

    /*public void addSource(Map<String, String> source) {
        if (source != null && !source.isEmpty()) {
            auditInfo.put(REQUEST_BODY, source);
        }
    }*/

    public void addIndices(String[] indices) {
        if (indices != null && indices.length > 0) {
            auditInfo.put(INDICES, indices);
        }

    }

    public void addResolvedIndices(String[] resolvedIndices) {
        if (resolvedIndices != null && resolvedIndices.length > 0) {
            auditInfo.put(RESOLVED_INDICES, resolvedIndices);
        }
    }

    public void addTaskId(long id) {
        auditInfo.put(TASK_ID, auditInfo.get(NODE_ID) + ":" + id);
    }

    public void addShardId(ShardId id) {
        if (id != null) {
            auditInfo.put(SHARD_ID, id.getId());
        }
    }

    public void addTaskParentId(String id) {
        if (id != null) {
            auditInfo.put(TASK_PARENT_ID, id);
        }
    }

    public void addRestParams(Map<String, String> params, AuditConfig.Filter filter) {
        if (params != null && !params.isEmpty()) {
            Map<String, String> redactedParams = new HashMap<>();
            for (Entry<String, String> param : params.entrySet()) {
                if (filter != null && filter.shouldExcludeUrlParam(param.getKey())) {
                    redactedParams.put(param.getKey(), "REDACTED");
                } else {
                    redactedParams.put(param.getKey(), param.getValue());
                }
            }
            auditInfo.put(REST_REQUEST_PARAMS, redactedParams);
        }
    }

    public void addRestHeaders(Map<String, List<String>> headers, boolean excludeSensitiveHeaders, AuditConfig.Filter filter) {
        if (headers != null && !headers.isEmpty()) {
            final Map<String, List<String>> headersClone = new HashMap<>(headers);
            if (excludeSensitiveHeaders) {
                headersClone.keySet().removeIf(AUTHORIZATION_HEADER);
            }
            if (filter != null) {
                headersClone.entrySet().removeIf(entry -> filter.shouldExcludeHeader(entry.getKey()));
            }
            auditInfo.put(REST_REQUEST_HEADERS, headersClone);
        }
    }

    void addRestMethod(final RestRequest.Method method) {
        if (method != null) {
            auditInfo.put(REST_REQUEST_METHOD, method);
        }
    }

    void addRestRequestInfo(final SecurityRequest request, final AuditConfig.Filter filter) {
        if (request != null) {
            final String path = request.path().toString();
            addPath(path);
            addRestHeaders(request.getHeaders(), filter.shouldExcludeSensitiveHeaders(), filter);
            addRestParams(request.params(), filter);
            addRestMethod(request.method());

            if (filter.shouldLogRequestBody()) {

                if (!(request instanceof OpenSearchRequest)) {
                    // The request body is only available on some request sources
                    return;
                }

                final OpenSearchRequest securityRestRequest = (OpenSearchRequest) request;
                final RestRequest restRequest = securityRestRequest.breakEncapsulationForRequest();

                if (!(restRequest.hasContentOrSourceParam())) {
                    // If there is no content, don't attempt to save any body information
                    return;
                }

                try {
                    final Tuple<MediaType, BytesReference> xContentTuple = restRequest.contentOrSourceParam();
                    final String requestBody = XContentHelper.convertToJson(xContentTuple.v2(), false, xContentTuple.v1());
                    if (path != null
                        && requestBody != null
                        && SENSITIVE_PATHS.matcher(path).matches()
                        && requestBody.contains(SENSITIVE_KEY)) {
                        auditInfo.put(REQUEST_BODY, SENSITIVE_REPLACEMENT_VALUE);
                    } else {
                        auditInfo.put(REQUEST_BODY, requestBody);
                    }
                } catch (Exception e) {
                    auditInfo.put(REQUEST_BODY, "ERROR: Unable to generate request body");
                    log.error("Error while generating request body for audit log", e);
                }
            }
        }
    }

    public void addTransportHeaders(Map<String, String> headers, boolean excludeSensitiveHeaders) {
        if (headers != null && !headers.isEmpty()) {
            final Map<String, String> headersClone = new HashMap<>(headers);
            if (excludeSensitiveHeaders) {
                headersClone.keySet().removeIf(AUTHORIZATION_HEADER);
            }
            auditInfo.put(TRANSPORT_REQUEST_HEADERS, headersClone);
        }
    }

    public void addComplianceOperation(Operation op) {
        if (op != null) {
            auditInfo.put(COMPLIANCE_OPERATION, op);
        }
    }

    public void addComplianceDocVersion(long version) {
        auditInfo.put(COMPLIANCE_DOC_VERSION, version);
    }

    public Map<String, Object> getAsMap() {
        return new HashMap<>(this.auditInfo);
    }

    public String getInitiatingUser() {
        return (String) this.auditInfo.get(REQUEST_INITIATING_USER);
    }

    public String getEffectiveUser() {
        return (String) this.auditInfo.get(REQUEST_EFFECTIVE_USER);
    }

    public String getRequestType() {
        return (String) this.auditInfo.get(TRANSPORT_REQUEST_TYPE);
    }

    public RestRequest.Method getRequestMethod() {
        return (RestRequest.Method) this.auditInfo.get(REST_REQUEST_METHOD);
    }

    public AuditCategory getCategory() {
        return msgCategory;
    }

    public Origin getOrigin() {
        return (Origin) this.auditInfo.get(ORIGIN);
    }

    public String getPrivilege() {
        return (String) this.auditInfo.get(PRIVILEGE);
    }

    public String getExceptionStackTrace() {
        return (String) this.auditInfo.get(EXCEPTION);
    }

    public String getRequestBody() {
        return (String) this.auditInfo.get(REQUEST_BODY);
    }

    public String getNodeId() {
        return (String) this.auditInfo.get(NODE_ID);
    }

    public String getDocId() {
        return (String) this.auditInfo.get(ID);
    }

    @Override
    public String toString() {
        try {
            return JsonXContent.contentBuilder().map(getAsMap()).toString();
        } catch (final IOException e) {
            throw ExceptionsHelper.convertToOpenSearchException(e);
        }
    }

    public String toPrettyString() {
        try {
            return JsonXContent.contentBuilder().prettyPrint().map(getAsMap()).toString();
        } catch (final IOException e) {
            throw ExceptionsHelper.convertToOpenSearchException(e);
        }
    }

    public String toText() {
        StringBuilder builder = new StringBuilder();
        for (Entry<String, Object> entry : getAsMap().entrySet()) {
            addIfNonEmpty(builder, entry.getKey(), stringOrNull(entry.getValue()));
        }
        return builder.toString();
    }

    public final String toJson() {
        return this.toString();
    }

    public String toUrlParameters() {
        URIBuilder builder = new URIBuilder();
        for (Entry<String, Object> entry : getAsMap().entrySet()) {
            builder.addParameter(entry.getKey(), stringOrNull(entry.getValue()));
        }
        return builder.toString();
    }

    protected static void addIfNonEmpty(StringBuilder builder, String key, String value) {
        if (!Strings.isEmpty(value)) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(key).append(": ").append(value);
        }
    }

    private String currentTime() {
        DateTime dt = new DateTime(DateTimeZone.UTC);
        return DEFAULT_FORMAT.print(dt);
    }

    private String formatTime(long epoch) {
        DateTime dt = new DateTime(epoch, DateTimeZone.UTC);
        return DEFAULT_FORMAT.print(dt);
    }

    protected String stringOrNull(Object object) {
        if (object == null) {
            return null;
        }

        return String.valueOf(object);
    }
}
