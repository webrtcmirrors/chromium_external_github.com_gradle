/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.http

import jcifs.http.NtlmSsp
import jcifs.smb.NtlmPasswordAuthentication
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.Request
import org.eclipse.jetty.security.ServerAuthException
import org.eclipse.jetty.server.Authentication
import org.mortbay.jetty.Response
import org.eclipse.jetty.security.Authenticator
import org.eclipse.jetty.security.Credential
import org.eclipse.jetty.security.UserRealm

import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletResponse
import java.security.Principal

class NtlmAuthenticator implements Authenticator {
    static final String NTLM_AUTH_METHOD = 'NTLM'

//    @Override
//    Principal authenticate(UserRealm realm, String pathInContext, Request request, Response response) throws IOException {
//        NtlmConnectionAuthentication connectionAuth = request.connection.associatedObject
//
//        if (connectionAuth == null) {
//            connectionAuth = new NtlmConnectionAuthentication(challenge: new byte[8])
//            new Random().nextBytes(connectionAuth.challenge)
//
//            request.connection.associatedObject = connectionAuth
//        }
//
//        if (connectionAuth.authenticated) {
//            request.authType = authMethod
//            request.userPrincipal = connectionAuth.principal
//
//            return connectionAuth.principal
//        } else {
//            NtlmPasswordAuthentication authentication = NtlmSsp.authenticate(request, response, connectionAuth.challenge)
//
//            if (authentication != null) {
//                Principal principal = realm.authenticate(authentication.username, new TestNtlmCredentials(authentication, connectionAuth.challenge), request)
//
//                if (principal != null) {
//                    request.authType = authMethod
//                    request.userPrincipal = principal
//                    connectionAuth.principal = principal
//
//                    return principal
//                } else {
//                    badCredentials(response)
//                }
//            }
//        }
//    }

    @Override
    void setConfiguration(AuthConfiguration configuration) {

    }

    @Override
    String getAuthMethod() {
        return NTLM_AUTH_METHOD
    }

    @Override
    void prepareRequest(ServletRequest request) {

    }

    @Override
    Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException {
        NtlmConnectionAuthentication connectionAuth = request.connection.associatedObject
        if (connectionAuth == null) {
            connectionAuth = new NtlmConnectionAuthentication(challenge: new byte[8])
            new Random().nextBytes(connectionAuth.challenge)

            request.connection.associatedObject = connectionAuth
        }

        if (connectionAuth.authenticated) {
            request.authType = authMethod
            request.userPrincipal = connectionAuth.principal

            return connectionAuth.principal
        }



        return null
    }

    @Override
    boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException {
        return false
    }

    private void badCredentials(Response response) {
        response.setHeader(HttpHeader.WWW_AUTHENTICATE, authMethod)
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
    }

    private static class TestNtlmCredentials extends Credential {
        private NtlmPasswordAuthentication authentication
        private byte[] challenge

        TestNtlmCredentials(NtlmPasswordAuthentication authentication, byte[] challenge) {
            this.authentication = authentication
            this.challenge = challenge
        }

        @Override
        boolean check(Object credentials) {
            if (credentials instanceof String) {
                byte[] hash = authentication.getAnsiHash(challenge)
                byte[] clientChallenge = hash[16..-1]

                return Arrays.equals(hash, NtlmPasswordAuthentication.getLMv2Response(authentication.domain, authentication.username, credentials, challenge, clientChallenge))
            }

            return false
        }
    }

    private static class NtlmConnectionAuthentication {
        byte[] challenge
        Principal principal

        boolean isAuthenticated() { principal != null }
    }
}
