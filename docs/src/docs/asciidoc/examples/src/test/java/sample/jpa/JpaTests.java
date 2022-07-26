/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sample.jpa;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sample.AuthorizationCodeGrantFlow;
import sample.jose.TestJwks;
import sample.jpa.service.authorization.JpaOAuth2AuthorizationService;
import sample.jpa.service.authorizationConsent.JpaOAuth2AuthorizationConsentService;
import sample.jpa.service.client.JpaRegisteredClientRepository;
import sample.test.SpringTestContext;
import sample.test.SpringTestContextExtension;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static sample.util.RegisteredClients.messagingClient;

/**
 * Tests for the guide How-to: Implement core services with JPA.
 *
 * @author Steve Riesenberg
 */
@ExtendWith(SpringTestContextExtension.class)
public class JpaTests {
	public final SpringTestContext spring = new SpringTestContext(this);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RegisteredClientRepository registeredClientRepository;

	@Autowired
	private OAuth2AuthorizationService authorizationService;

	@Autowired
	private OAuth2AuthorizationConsentService authorizationConsentService;

	@Test
	public void oidcLoginWhenJpaCoreServicesAutowiredThenUsed() throws Exception {
		this.spring.register(AuthorizationServerConfig.class).autowire();
		assertThat(this.registeredClientRepository).isInstanceOf(JpaRegisteredClientRepository.class);
		assertThat(this.authorizationService).isInstanceOf(JpaOAuth2AuthorizationService.class);
		assertThat(this.authorizationConsentService).isInstanceOf(JpaOAuth2AuthorizationConsentService.class);

		RegisteredClient registeredClient = messagingClient();
		this.registeredClientRepository.save(registeredClient);

		AuthorizationCodeGrantFlow authorizationCodeGrantFlow = new AuthorizationCodeGrantFlow(this.mockMvc);
		authorizationCodeGrantFlow.setUsername("user");
		authorizationCodeGrantFlow.addScope("message.read");
		authorizationCodeGrantFlow.addScope("message.write");

		String state = authorizationCodeGrantFlow.authorize(registeredClient);
		assertThatAuthorization(state, OAuth2ParameterNames.STATE).isNotNull();
		assertThatAuthorization(state, null).isNotNull();

		String authorizationCode = authorizationCodeGrantFlow.submitConsent(registeredClient, state);
		assertThatAuthorization(authorizationCode, OAuth2ParameterNames.CODE).isNotNull();
		assertThatAuthorization(authorizationCode, null).isNotNull();

		Map<String, Object> tokenResponse = authorizationCodeGrantFlow.getTokenResponse(registeredClient, authorizationCode);
		String accessToken = (String) tokenResponse.get(OAuth2ParameterNames.ACCESS_TOKEN);
		assertThatAuthorization(accessToken, OAuth2ParameterNames.ACCESS_TOKEN).isNotNull();
		assertThatAuthorization(accessToken, null).isNotNull();

		String refreshToken = (String) tokenResponse.get(OAuth2ParameterNames.REFRESH_TOKEN);
		assertThatAuthorization(refreshToken, OAuth2ParameterNames.REFRESH_TOKEN).isNotNull();
		assertThatAuthorization(refreshToken, null).isNotNull();

		String idToken = (String) tokenResponse.get(OidcParameterNames.ID_TOKEN);
		assertThatAuthorization(idToken, OidcParameterNames.ID_TOKEN).isNull(); // id_token is not searchable

		OAuth2Authorization authorization = findAuthorization(accessToken, OAuth2ParameterNames.ACCESS_TOKEN);
		assertThat(authorization.getToken(idToken)).isNotNull();

		String scopes = (String) tokenResponse.get(OAuth2ParameterNames.SCOPE);
		OAuth2AuthorizationConsent authorizationConsent = this.authorizationConsentService.findById(
				registeredClient.getId(), "user");
		assertThat(authorizationConsent).isNotNull();
		assertThat(authorizationConsent.getScopes()).containsExactlyInAnyOrder(
				StringUtils.delimitedListToStringArray(scopes, " "));
	}

	private ObjectAssert<OAuth2Authorization> assertThatAuthorization(String token, String tokenType) {
		return assertThat(findAuthorization(token, tokenType));
	}

	private OAuth2Authorization findAuthorization(String token, String tokenType) {
		return this.authorizationService.findByToken(token, tokenType == null ? null : new OAuth2TokenType(tokenType));
	}

	@EnableWebSecurity
	@EnableAutoConfiguration
	@ComponentScan
	@Import(OAuth2AuthorizationServerConfiguration.class)
	static class AuthorizationServerConfig {

		@Bean
		public JWKSource<SecurityContext> jwkSource() {
			JWKSet jwkSet = new JWKSet(TestJwks.DEFAULT_RSA_JWK);
			return new ImmutableJWKSet<>(jwkSet);
		}

		@Bean
		public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
			return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
		}

	}

}
