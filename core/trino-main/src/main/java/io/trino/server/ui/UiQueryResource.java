/*
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
package io.trino.server.ui;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler;
import io.trino.dispatcher.DispatchManager;
import io.trino.execution.QueryInfo;
import io.trino.execution.QueryState;
import io.trino.security.AccessControl;
import io.trino.server.BasicQueryInfo;
import io.trino.server.DisableHttpCache;
import io.trino.server.GoneException;
import io.trino.server.HttpRequestSessionContextFactory;
import io.trino.server.security.ResourceSecurity;
import io.trino.spi.QueryId;
import io.trino.spi.TrinoException;
import io.trino.spi.security.AccessDeniedException;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static io.trino.connector.system.KillQueryProcedure.createKillQueryException;
import static io.trino.connector.system.KillQueryProcedure.createPreemptQueryException;
import static io.trino.security.AccessControlUtil.checkCanKillQueryOwnedBy;
import static io.trino.security.AccessControlUtil.checkCanViewQueryOwnedBy;
import static io.trino.security.AccessControlUtil.filterQueries;
import static io.trino.server.security.ResourceSecurity.AccessType.WEB_UI;
import static java.util.Objects.requireNonNull;

@Path("/ui/api/query")
@ResourceSecurity(WEB_UI)
@DisableHttpCache
public class UiQueryResource
{
    private final DispatchManager dispatchManager;
    private final AccessControl accessControl;
    private final HttpRequestSessionContextFactory sessionContextFactory;
    private final HttpClient httpClient;
    @Nullable private final String historyServerUrl;
    @Nullable private final String historyQueryPath;

    @Inject
    public UiQueryResource(DispatchManager dispatchManager, AccessControl accessControl, HttpRequestSessionContextFactory sessionContextFactory, @ForWebUi HttpClient httpClient, WebUiConfig webUiConfig)
    {
        this.dispatchManager = requireNonNull(dispatchManager, "dispatchManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.sessionContextFactory = requireNonNull(sessionContextFactory, "sessionContextFactory is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.historyServerUrl = webUiConfig.getHistoryServerUrl();
        this.historyQueryPath = webUiConfig.getHistoryQueryPath();
    }

    @GET
    public List<TrimmedBasicQueryInfo> getAllQueryInfo(@QueryParam("state") String stateFilter, @Context HttpServletRequest servletRequest, @Context HttpHeaders httpHeaders)
    {
        QueryState expectedState = stateFilter == null ? null : QueryState.valueOf(stateFilter.toUpperCase(Locale.ENGLISH));

        List<BasicQueryInfo> queries = dispatchManager.getQueries();
        queries = filterQueries(sessionContextFactory.extractAuthorizedIdentity(servletRequest, httpHeaders), queries, accessControl);

        ImmutableList.Builder<TrimmedBasicQueryInfo> builder = ImmutableList.builder();
        for (BasicQueryInfo queryInfo : queries) {
            if (stateFilter == null || queryInfo.getState() == expectedState) {
                builder.add(new TrimmedBasicQueryInfo(queryInfo));
            }
        }
        return builder.build();
    }

    @GET
    @Path("{queryId}")
    public Response getQueryInfo(@PathParam("queryId") QueryId queryId, @Context HttpServletRequest servletRequest, @Context HttpHeaders httpHeaders)
    {
        requireNonNull(queryId, "queryId is null");

        // Patch: performing an HTTP request to REST history server for historical query info (JSON)
        if (historyServerUrl != null) {
            return getQueryInfoFromHistoryServer(queryId);
        }

        Optional<QueryInfo> queryInfo = dispatchManager.getFullQueryInfo(queryId);
        if (queryInfo.isPresent()) {
            try {
                checkCanViewQueryOwnedBy(sessionContextFactory.extractAuthorizedIdentity(servletRequest, httpHeaders), queryInfo.get().getSession().toIdentity(), accessControl);
                return Response.ok(queryInfo.get().pruneDigests()).build();
            }
            catch (AccessDeniedException e) {
                throw new ForbiddenException();
            }
        }
        throw new GoneException();
    }

    private Response getQueryInfoFromHistoryServer(QueryId queryId)
    {
        URI address = URI.create(historyServerUrl + historyQueryPath + queryId.toString());
        Request request = prepareGet().setUri(address).build();
        StringResponseHandler.StringResponse response;

        try {
            response = httpClient.execute(request, createStringResponseHandler());
        }
        catch (RuntimeException e) {
            throw new InternalServerErrorException("Error getting query info from " + address, e);
        }
        if (response.getStatusCode() == 400) {
            throw new GoneException();
        }
        if (response.getStatusCode() == 500) {
            throw new InternalServerErrorException();
        }

        return Response.ok(response.getBody(), MediaType.APPLICATION_JSON).build();
    }

    @PUT
    @Path("{queryId}/killed")
    public Response killQuery(@PathParam("queryId") QueryId queryId, String message, @Context HttpServletRequest servletRequest, @Context HttpHeaders httpHeaders)
    {
        return failQuery(queryId, createKillQueryException(message), servletRequest, httpHeaders);
    }

    @PUT
    @Path("{queryId}/preempted")
    public Response preemptQuery(@PathParam("queryId") QueryId queryId, String message, @Context HttpServletRequest servletRequest, @Context HttpHeaders httpHeaders)
    {
        return failQuery(queryId, createPreemptQueryException(message), servletRequest, httpHeaders);
    }

    private Response failQuery(QueryId queryId, TrinoException queryException, HttpServletRequest servletRequest, @Context HttpHeaders httpHeaders)
    {
        requireNonNull(queryId, "queryId is null");

        try {
            BasicQueryInfo queryInfo = dispatchManager.getQueryInfo(queryId);

            checkCanKillQueryOwnedBy(sessionContextFactory.extractAuthorizedIdentity(servletRequest, httpHeaders), queryInfo.getSession().toIdentity(), accessControl);

            // check before killing to provide the proper error code (this is racy)
            if (queryInfo.getState().isDone()) {
                return Response.status(Status.CONFLICT).build();
            }

            dispatchManager.failQuery(queryId, queryException);

            return Response.status(Status.ACCEPTED).build();
        }
        catch (AccessDeniedException e) {
            throw new ForbiddenException();
        }
        catch (NoSuchElementException e) {
            throw new GoneException();
        }
    }
}
