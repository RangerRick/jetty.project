//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.authentication;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeferredAuthentication implements Authentication
{
    private static final Logger LOG = LoggerFactory.getLogger(DeferredAuthentication.class);
    protected final LoginAuthenticator _authenticator;
    private IdentityService.Association _association;

    public DeferredAuthentication(LoginAuthenticator authenticator)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
    }

    public Authentication.User authenticate(Request request)
    {
        try
        {
            Authentication authentication = _authenticator.validateRequest(request, __deferredResponse, null);
            if (authentication instanceof Authentication.User user)
            {
                LoginService loginService = _authenticator.getLoginService();
                IdentityService identityService = loginService.getIdentityService();

                if (identityService != null)
                    _association = identityService.associate(user.getUserIdentity());

                return user;
            }
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }

        return null;
    }

    public Authentication authenticate(Request request, Response response, Callback callback)
    {
        try
        {
            LoginService loginService = _authenticator.getLoginService();
            IdentityService identityService = loginService.getIdentityService();

            Authentication authentication = _authenticator.validateRequest(request, response, callback);
            if (authentication instanceof Authentication.User && identityService != null)
                _association = identityService.associate(((Authentication.User)authentication).getUserIdentity());
            return authentication;
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }
        return null;
    }

    public Authentication.User login(String username, Object password, Request request, Response response)
    {
        if (username == null)
            return null;

        UserIdentity identity = _authenticator.login(username, password, request, response);
        if (identity != null)
        {
            IdentityService identityService = _authenticator.getLoginService().getIdentityService();
            UserAuthentication authentication = new UserAuthentication("API", identity);
            if (identityService != null)
                _association = identityService.associate(identity);
            return authentication;
        }
        return null;
    }

    public IdentityService.Association getAssociation()
    {
        return _association;
    }

    /**
     * @param response the response
     * @return true if this response is from a deferred call to {@link #authenticate(Request)}
     */
    public static boolean isDeferred(Response response)
    {
        return response == __deferredResponse;
    }

    private static final Response __deferredResponse = new Response()
    {
        @Override
        public Request getRequest()
        {
            return null;
        }

        @Override
        public int getStatus()
        {
            return 0;
        }

        @Override
        public void setStatus(int code)
        {
        }

        @Override
        public Mutable getHeaders()
        {
            return null;
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public boolean isCommitted()
        {
            return true;
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return false;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            return null;
        }
    };
}
