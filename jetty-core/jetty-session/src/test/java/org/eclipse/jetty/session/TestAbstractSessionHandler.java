//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpCookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class TestAbstractSessionHandler
{
    @Test
    public void testGetSessionCookie() throws Exception
    {
        //Make a session
        SessionData sessionData = new SessionData("1234", "_test", "0.0.0.0", 100, 200, 200, -1);
        TestableSessionHandler sessionHandler = new TestableSessionHandler(); 
        Session session = new Session(sessionHandler, sessionData);
        session.setExtendedId("1234.foo");
        session.getSessionData().setLastNode("foo");

        //check cookie with all default cookie config settings
        HttpCookie cookie = sessionHandler.getSessionCookie(session, "/test", false);
        assertNotNull(cookie);
        assertEquals(SessionManager.__DefaultSessionCookie, cookie.getName());
        assertEquals(SessionManager.__DefaultSessionDomain, cookie.getDomain());
        assertEquals("/test", cookie.getPath());
        assertFalse(cookie.isSecure());
        assertFalse(cookie.isHttpOnly());
        
        //check cookie with httpOnly and secure
        sessionHandler.setHttpOnly(true);
        sessionHandler.setSecureRequestOnly(true);
        sessionHandler.setSecureCookies(true);
        cookie = sessionHandler.getSessionCookie(session, "/test", true);
        assertNotNull(cookie);
        assertEquals(SessionManager.__DefaultSessionCookie, cookie.getName());
        assertEquals(SessionManager.__DefaultSessionDomain, cookie.getDomain());
        assertEquals("/test", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        
        //check cookie when cookie config is set
        sessionHandler.getCookieConfig().put(SessionManager.__SessionCookieProperty, "MYSESSIONID");
        sessionHandler.getCookieConfig().put(SessionManager.__SessionDomainProperty, "foo.bar");
        sessionHandler.getCookieConfig().put(SessionManager.__SessionPathProperty, "/special");
        sessionHandler.configureCookies();
        cookie = sessionHandler.getSessionCookie(session, "/test", false);
        assertNotNull(cookie);
        assertEquals("MYSESSIONID", cookie.getName());
        assertEquals("foo.bar", cookie.getDomain());
        assertEquals("/special", cookie.getPath());
        assertTrue(cookie.isSecure());
        assertTrue(cookie.isHttpOnly());
    }
    
    @Test
    public void testAccess() throws Exception
    {
        //Make a session
        SessionData sessionData = new SessionData("1234", "_test", "0.0.0.0", 100, 200, 200, -1);
        TestableSessionHandler sessionHandler = new TestableSessionHandler(); 
        Session session = new Session(sessionHandler, sessionData);
        session.setExtendedId("1234.foo");
        session.getSessionData().setLastNode("foo");
        session.setResident(true); //pretend its in a cache
        
        //not using cookies
        sessionHandler.setUsingCookies(false);
        HttpCookie cookie = sessionHandler.access(session, false);
        assertNull(cookie);
        
        //session cookies never expire, shouldn't create a new one
        sessionHandler.setUsingCookies(true);
        session.getSessionData().setCookieSet(0);
        cookie = sessionHandler.access(session, false);
        assertNull(cookie);
        
        //session cookies expire, should create a new one
        session.getSessionData().setCookieSet(300); //time session cookie was set
        sessionHandler.setRefreshCookieAge(10); //cookie reset after 10sec
        sessionHandler.setMaxCookieAge(5); //cookies cannot be older than 5 sec
        cookie = sessionHandler.access(session, false);
        assertNotNull(cookie);
    }

    @Test
    public void testCalculateInactivityTimeOut() throws Exception
    {
        //Make a session
        TestableSessionHandler sessionHandler = new TestableSessionHandler(); 

        AbstractSessionCache sessionCache = new AbstractSessionCache(sessionHandler)
        {
            @Override
            public void shutdown()
            {
            }

            @Override
            public Session newSession(SessionData data)
            {
                return null;
            }

            @Override
            protected Session doGet(String id)
            {
                return null;
            }

            @Override
            protected Session doPutIfAbsent(String id, Session session)
            {
                return null;
            }

            @Override
            protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
            {
                return null;
            }

            @Override
            protected boolean doReplace(String id, Session oldValue, Session newValue)
            {
                return false;
            }

            @Override
            public Session doDelete(String id)
            {
                return null;
            }
        };
        sessionHandler.setSessionCache(sessionCache);
        
        //calculate inactivity time when sessions are never evicted && they are immortal
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        long timeToExpiry = 0;
        long maxIdleTime = -1;
        long timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(-1, timeout); //no timeout
        
        //calculate inactivity time when sessions are evicted && they are immortal
        int evictionTimeout = 1; //period after which session should be evicted
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(TimeUnit.SECONDS.toMillis(evictionTimeout), timeout); //inactivity timeout == the eviction timeout
        
        //calculate inactivity time when sessions are never evicted && they are mortal
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        timeToExpiry = 1000; //session has 1sec remaining before expiry
        maxIdleTime = 20000; //sessions only last 20sec
        timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, 20000);
        assertEquals(timeToExpiry, timeout);

        //calculate inactivity time when sessions are evicted && they are mortal
        evictionTimeout = 5; //will be evicted after 5sec inactivity
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(TimeUnit.SECONDS.toMillis(evictionTimeout), timeout); //the eviction timeout is smaller than the maxIdle timeout

        //calculate inactivity time when sessions are evicted && they are mortal, session expired
        timeToExpiry = 0;
        timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(0, timeout); //no timeout for an expired session
      
        //calculate inactivity time when sessions are evicted && they are mortal, session expired
        maxIdleTime = 20000; //sessions only last 20sec
        evictionTimeout = 30; //idle eviction timeout is 30sec
        timeToExpiry = 1000; //session not yet expired
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionHandler.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(maxIdleTime, timeout); //the maxIdleTime is smaller than the eviction timeout
    }
}
