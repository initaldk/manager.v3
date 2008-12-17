// Copyright (C) 2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.manager;

import com.google.enterprise.connector.spi.AuthenticationIdentity;
import com.google.enterprise.connector.spi.AuthenticationManager;
import com.google.enterprise.connector.spi.AuthenticationResponse;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.RepositoryLoginException;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.Cookie;

/**
 * Calls the authenticate method of a supplied {@link AuthenticationManager},
 * using a supplied {@link AuthenticationIdentity}, but first reconciles the
 * identity with a security context Map, and also reconciles the context on the
 * way out. If no security context is supplied, this amounts to simply calling
 * the authentication manager directly.
 */
public class AuthnCaller {

  private static final Logger LOGGER = Logger.getLogger(AuthnCaller.class.getName());

  private AuthenticationManager authnManager;
  private AuthenticationIdentity identity;
  private final List<Cookie> securityContext;

  public AuthnCaller(AuthenticationManager authnManager, AuthenticationIdentity identity,
      List<Cookie> securityContext) {
    this.authnManager = authnManager;
    this.identity = identity;
    this.securityContext = securityContext;
  }

  public AuthenticationResponse authenticate() {
    if (authnManager == null) {
      return null;
    }
    initializeCookiesFromContext();
    AuthenticationResponse authenticationResponse = null;
    try {
      authenticationResponse = authnManager.authenticate(identity);
    } catch (RepositoryLoginException e) {
      LOGGER.log(Level.WARNING, "Login: ", e);
    } catch (RepositoryException e) {
      LOGGER.log(Level.WARNING, "Repository: ", e);
    }

    reconcileReturnedCookiesWithContext();
    return authenticationResponse;
  }

  private void initializeCookiesFromContext() {
    if (securityContext != null) {
      for (Cookie c: securityContext) {
        identity.setCookie(c.getName(), c.getValue());
      }
    }
  }

  /**
   * This implementation currently just throws away any changes the
   * authentication manager may have made to pre-existing cookies. It only adds
   * the values for new cookies.
   */
  private void reconcileReturnedCookiesWithContext() {
    if (securityContext != null) {
      for (Object o: identity.getCookieNames()) {
        String name = String.class.cast(o);
        if (!knownCookie(name)) {
          securityContext.add(new Cookie(name, identity.getCookie(name)));
        }
      }
    }
  }

  private boolean knownCookie(String name) {
    for (Cookie c: securityContext) {
      if (c.getName().equals(name)) {
        return true;
      }
    }
    return false;
  }
}
