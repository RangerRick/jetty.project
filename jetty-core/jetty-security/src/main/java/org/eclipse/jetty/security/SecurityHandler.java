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

package org.eclipse.jetty.security;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract SecurityHandler.
 * <p>
 * Select and apply an {@link Authenticator} to a request.
 * <p>
 * The Authenticator may either be directly set on the handler
 * or it will be created during {@link #start()} with a call to
 * either the default or set AuthenticatorFactory.
 * <p>
 * SecurityHandler has a set of parameters that are used by the
 * Authentication.Configuration. At startup, any context init parameters
 * that start with "org.eclipse.jetty.security." that do not have
 * values in the SecurityHandler init parameters, are copied.
 */
public abstract class SecurityHandler extends Handler.Wrapper implements AuthConfiguration
{
    public static String SESSION_AUTHENTICATED_ATTRIBUTE = "org.eclipse.jetty.security.sessionAuthenticated";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityHandler.class);
    private static final List<Authenticator.Factory> __knownAuthenticatorFactories = new ArrayList<>();

    private Authenticator _authenticator;
    private Authenticator.Factory _authenticatorFactory;
    private String _realmName;
    private String _authMethod;
    private final Map<String, String> _parameters = new HashMap<>();
    private LoginService _loginService;
    private IdentityService _identityService;
    private boolean _renewSession = true;
    DeferredAuthentication _deferredAuthentication;

    static
    {
        TypeUtil.serviceStream(ServiceLoader.load(Authenticator.Factory.class))
            .forEach(__knownAuthenticatorFactories::add);
        __knownAuthenticatorFactories.add(new DefaultAuthenticatorFactory());
    }

    protected SecurityHandler()
    {
        addBean(new DumpableCollection("knownAuthenticatorFactories", __knownAuthenticatorFactories));
    }

    /**
     * Get the identityService.
     *
     * @return the identityService
     */
    @Override
    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    /**
     * Set the identityService.
     *
     * @param identityService the identityService to set
     */
    public void setIdentityService(IdentityService identityService)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_identityService, identityService);
        _identityService = identityService;
    }

    /**
     * Get the loginService.
     *
     * @return the loginService
     */
    @Override
    public LoginService getLoginService()
    {
        return _loginService;
    }

    /**
     * Set the loginService.
     *
     * @param loginService the loginService to set
     */
    public void setLoginService(LoginService loginService)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_loginService, loginService);
        _loginService = loginService;
    }

    public Authenticator getAuthenticator()
    {
        return _authenticator;
    }

    /**
     * Set the authenticator.
     *
     * @param authenticator the authenticator
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthenticator(Authenticator authenticator)
    {
        if (isStarted())
            throw new IllegalStateException("Started");
        updateBean(_authenticator, authenticator);
        _authenticator = authenticator;
        if (_authenticator != null)
            _authMethod = _authenticator.getAuthMethod();
    }

    /**
     * @return the authenticatorFactory
     */
    public Authenticator.Factory getAuthenticatorFactory()
    {
        return _authenticatorFactory;
    }

    /**
     * @param authenticatorFactory the authenticatorFactory to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthenticatorFactory(Authenticator.Factory authenticatorFactory)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        updateBean(_authenticatorFactory, authenticatorFactory);
        _authenticatorFactory = authenticatorFactory;
    }

    /**
     * @return the list of discovered authenticatorFactories
     */
    public List<Authenticator.Factory> getKnownAuthenticatorFactories()
    {
        return __knownAuthenticatorFactories;
    }

    /**
     * @return the realmName
     */
    @Override
    public String getRealmName()
    {
        return _realmName;
    }

    /**
     * @param realmName the realmName to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setRealmName(String realmName)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _realmName = realmName;
    }

    /**
     * @return the authMethod
     */
    @Override
    public String getAuthMethod()
    {
        return _authMethod;
    }

    /**
     * @param authMethod the authMethod to set
     * @throws IllegalStateException if the SecurityHandler is running
     */
    public void setAuthMethod(String authMethod)
    {
        if (isRunning())
            throw new IllegalStateException("running");
        _authMethod = authMethod;
        if (_authenticator != null && !_authenticator.getAuthMethod().equals(_authMethod))
            _authenticator = null;
    }

    @Override
    public String getParameter(String key)
    {
        return _parameters.get(key);
    }

    @Override
    public Set<String> getParameterNames()
    {
        return _parameters.keySet();
    }

    /**
     * Set an authentication parameter for retrieval via {@link AuthConfiguration#getParameter(String)}
     *
     * @param key the key
     * @param value the init value
     * @return previous value
     * @throws IllegalStateException if the SecurityHandler is started
     */
    public String setParameter(String key, String value)
    {
        if (isStarted())
            throw new IllegalStateException("started");
        return _parameters.put(key, value);
    }

    protected LoginService findLoginService() throws Exception
    {
        java.util.Collection<LoginService> list = getServer().getBeans(LoginService.class);
        LoginService service = null;
        String realm = getRealmName();
        if (realm != null)
        {
            for (LoginService s : list)
            {
                if (s.getName() != null && s.getName().equals(realm))
                {
                    service = s;
                    break;
                }
            }
        }
        else if (list.size() == 1)
            service = list.iterator().next();

        return service;
    }

    protected IdentityService findIdentityService()
    {
        return getServer().getBean(IdentityService.class);
    }

    @Override
    protected void doStart()
        throws Exception
    {
        // complicated resolution of login and identity service to handle
        // many different ways these can be constructed and injected.

        if (_loginService == null)
        {
            setLoginService(findLoginService());
            if (_loginService != null)
                unmanage(_loginService);
        }

        if (_identityService == null)
        {
            if (_loginService != null)
                setIdentityService(_loginService.getIdentityService());

            if (_identityService == null)
                setIdentityService(findIdentityService());

            if (_identityService == null)
            {
                setIdentityService(new DefaultIdentityService());
                manage(_identityService);
            }
            else
                unmanage(_identityService);
        }

        if (_loginService != null)
        {
            if (_loginService.getIdentityService() == null)
                _loginService.setIdentityService(_identityService);
            else if (_loginService.getIdentityService() != _identityService)
                throw new IllegalStateException("LoginService has different IdentityService to " + this);
        }

        Context context = ContextHandler.getCurrentContext();

        if (_authenticator == null)
        {
            // If someone has set an authenticator factory only use that, otherwise try the list of discovered factories.
            if (_authenticatorFactory != null)
            {
                Authenticator authenticator = _authenticatorFactory.getAuthenticator(getServer(), context,
                    this);

                if (authenticator != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Created authenticator {} with {}", authenticator, _authenticatorFactory);

                    setAuthenticator(authenticator);
                }
            }
            else
            {
                for (Authenticator.Factory factory : getKnownAuthenticatorFactories())
                {
                    Authenticator authenticator = factory.getAuthenticator(getServer(), context,
                        this);

                    if (authenticator != null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Created authenticator {} with {}", authenticator, factory);

                        setAuthenticator(authenticator);
                        break;
                    }
                }
            }
        }

        if (_authenticator == null)
            setAuthenticator(new Authenticator.Null());

        if (_authenticator != null)
            _authenticator.setConfiguration(this);
        else if (_realmName != null)
        {
            LOG.warn("No Authenticator for {}", this);
            throw new IllegalStateException("No Authenticator");
        }

        if (_authenticator instanceof LoginAuthenticator loginAuthenticator)
        {
            _deferredAuthentication = new DeferredAuthentication(loginAuthenticator);
            addBean(_deferredAuthentication);
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        //if we discovered the services (rather than had them explicitly configured), remove them.
        if (!isManaged(_identityService))
        {
            removeBean(_identityService);
            _identityService = null;
        }

        if (!isManaged(_loginService))
        {
            removeBean(_loginService);
            _loginService = null;
        }

        if (_deferredAuthentication != null)
        {
            removeBean(_deferredAuthentication);
            _deferredAuthentication = null;
        }
        super.doStop();
    }

    @Override
    public boolean isSessionRenewedOnAuthentication()
    {
        return _renewSession;
    }

    /**
     * Set renew the session on Authentication.
     * <p>
     * If set to true, then on authentication, the session associated with a reqeuest is invalidated and replaced with a new session.
     *
     * @param renew true to renew the authentication on session
     * @see AuthConfiguration#isSessionRenewedOnAuthentication()
     */
    public void setSessionRenewedOnAuthentication(boolean renew)
    {
        _renewSession = renew;
    }
    
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        String pathInContext = Request.getPathInContext(request);
        Constraint constraint = getConstraint(pathInContext, request);
        if (constraint == null)
            constraint = Constraint.NONE;

        if (constraint.isForbidden())
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return true;
        }

        // Check data constraints
        if (constraint.isSecure() && !request.isSecure())
        {
            redirectToSecure(request, response, callback);
            return true;
        }

        // Determine Constraint.Authentication
        Constraint.Authentication constraintAuthentication = constraint.getAuthentication();
        constraintAuthentication = _authenticator.getConstraintAuthentication(pathInContext, constraintAuthentication, request::getSession);
        boolean mustValidate = constraintAuthentication != Constraint.Authentication.REQUIRE_NONE;

        try
        {
            Authentication authentication = mustValidate ? _authenticator.validateRequest(request, response, callback) : null;

            if (authentication instanceof Authentication.ResponseSent)
                return true;

            if (isNotAuthorized(constraint, authentication))
            {
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "!authorized");
                return true;
            }

            if (authentication == null)
                authentication = _deferredAuthentication;

            Authentication.setAuthentication(request, authentication);
            IdentityService.Association association = authentication instanceof Authentication.User user
                ? _identityService.associate(user.getUserIdentity())
                : null;

            try
            {
                //process the request by other handlers
                return next.handle(_authenticator.prepareRequest(request, authentication), response, callback);
            }
            finally
            {
                if (association == null && authentication instanceof DeferredAuthentication deferred)
                    association = deferred.getAssociation();
                if (association != null)
                    association.close();
            }
        }
        catch (ServerAuthException e)
        {
            Response.writeError(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500, e.getMessage());
            return true;
        }
    }

    public static SecurityHandler getCurrentSecurityHandler()
    {
        ContextHandler contextHandler = ContextHandler.getCurrentContextHandler();
        if (contextHandler != null)
            return contextHandler.getDescendant(SecurityHandler.class);
        // TODO what about without context?
        return null;
    }

    public void logout(Authentication.User user)
    {
        LOG.debug("logout {}", user);
        if (user == null)
            return;

        LoginService loginService = getLoginService();
        if (loginService != null)
            loginService.logout(user.getUserIdentity());

        IdentityService identityService = getIdentityService();
        if (identityService != null)
            identityService.logout(user.getUserIdentity());
    }

    protected abstract Constraint getConstraint(String pathInContext, Request request);

    protected void redirectToSecure(Request request, Response response, Callback callback)
    {
        HttpConfiguration httpConfig = request.getConnectionMetaData().getHttpConfiguration();
        if (httpConfig.getSecurePort() > 0)
        {
            //Redirect to secure port
            String scheme = httpConfig.getSecureScheme();
            int port = httpConfig.getSecurePort();

            String url = URIUtil.newURI(scheme, Request.getServerName(request), port, request.getHttpURI().getPath(), request.getHttpURI().getQuery());
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);

            Response.sendRedirect(request, response, callback, HttpStatus.MOVED_TEMPORARILY_302, url, true);
        }
        else
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "!Secure");
        }
    }

    protected boolean isNotAuthorized(Constraint constraint, Authentication authentication)
    {
        UserIdentity userIdentity = authentication instanceof Authentication.User user ? user.getUserIdentity() : null;

        return switch (constraint.getAuthentication())
        {
            case REQUIRE_NONE -> false;
            case REQUIRE_ANY_ROLE -> userIdentity == null || userIdentity.getUserPrincipal() == null;
            case REQUIRE_KNOWN_ROLE ->
            {
                if (userIdentity != null && userIdentity.getUserPrincipal() != null)
                    for (String role : getKnownRoles())
                        if (userIdentity.isUserInRole(role))
                            yield false;
                yield true;
            }

            case REQUIRE_SPECIFIC_ROLE ->
            {
                if (userIdentity != null && userIdentity.getUserPrincipal() != null)
                    for (String role : constraint.getRoles())
                        if (userIdentity.isUserInRole(role))
                            yield false;
                yield true;
            }
        };
    }

    protected Set<String> getKnownRoles()
    {
        return Collections.emptySet();
    }

    public class NotChecked implements Principal
    {
        @Override
        public String getName()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return "NOT CHECKED";
        }

        public SecurityHandler getSecurityHandler()
        {
            return SecurityHandler.this;
        }
    }

    public static final Principal __NO_USER = new Principal()
    {
        @Override
        public String getName()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return "No User";
        }
    };

    /**
     * Nobody user. The Nobody UserPrincipal is used to indicate a partial state
     * of authentication. A request with a Nobody UserPrincipal will be allowed
     * past all authentication constraints - but will not be considered an
     * authenticated request. It can be used by Authenticators such as
     * FormAuthenticator to allow access to logon and error pages within an
     * authenticated URI tree.
     */
    public static final Principal __NOBODY = new Principal()
    {
        @Override
        public String getName()
        {
            return "Nobody";
        }

        @Override
        public String toString()
        {
            return getName();
        }
    };

    public static class Mapped extends SecurityHandler
    {
        private final PathMappings<Constraint> _mappings = new PathMappings<>();
        private final Set<String> _knownRoles = new HashSet<>();

        public Mapped()
        {
        }

        public Constraint put(String pathSpec, Constraint constraint)
        {
            return put(PathSpec.from(pathSpec), constraint);
        }

        public Constraint put(PathSpec pathSpec, Constraint constraint)
        {
            Set<String> roles = constraint.getRoles();
            if (roles != null)
                _knownRoles.addAll(roles);
            return _mappings.put(pathSpec, constraint);
        }

        public Constraint get(PathSpec pathSpec)
        {
            return _mappings.get(pathSpec);
        }

        public Constraint remove(PathSpec pathSpec)
        {
            Constraint removed = _mappings.remove(pathSpec);
            _knownRoles.clear();
            _mappings.values().forEach(c ->
            {
                Set<String> roles = c.getRoles();
                if (roles != null)
                    _knownRoles.addAll(roles);

            });
            return removed;
        }

        @Override
        protected Constraint getConstraint(String pathInContext, Request request)
        {
            List<MappedResource<Constraint>> matches = _mappings.getMatches(pathInContext);
            if (matches == null || matches.isEmpty())
                return null;

            if (matches.size() == 1)
                return matches.get(0).getResource();

            Constraint constraint = null;
            for (MappedResource<Constraint> c : matches)
                constraint = Constraint.combine(constraint, c.getResource());

            return constraint;
        }

        @Override
        protected Set<String> getKnownRoles()
        {
            return _knownRoles;
        }
    }
}
