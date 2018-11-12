/*
 * Copyright 2013-2017 Erudika. https://erudika.com
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
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.Para;
import com.erudika.para.core.App;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.User;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.rest.RestUtils;
import com.erudika.para.security.filters.*;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.GenericFilterBean;

import javax.inject.Inject;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Security filter that intercepts authentication requests (usually coming from the client-side)
 * and validates JWT tokens.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class JWTRestfulAuthFilter extends GenericFilterBean {

	private AuthenticationManager authenticationManager;
	private AntPathRequestMatcher authenticationRequestMatcher;

	private FacebookAuthFilter facebookAuth;
	private GoogleAuthFilter googleAuth;
	private GitHubAuthFilter githubAuth;
	private LinkedInAuthFilter linkedinAuth;
	private TwitterAuthFilter twitterAuth;
	private MicrosoftAuthFilter microsoftAuth;
	private GenericOAuth2Filter oauth2Auth;
	private LdapAuthFilter ldapAuth;
	private PasswordAuthFilter passwordAuth;
	private WechatAuthFilter wechatAuth;
	private VerificationCodeAuthFilter verificationCodeAuth;

	/**
	 * The default filter mapping.
	 */
	public static final String JWT_ACTION = "jwt_auth";

	/**
	 * Default constructor.
	 * @param defaultFilterProcessesUrl filter URL
	 */
	public JWTRestfulAuthFilter(String defaultFilterProcessesUrl) {
		setFilterProcessesUrl(defaultFilterProcessesUrl);
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) res;
		if (authenticationRequestMatcher.matches(request)) {
			if (HttpMethod.POST.equals(request.getMethod())) {
				newTokenHandler(request, response);
			} else if (HttpMethod.GET.equals(request.getMethod())) {
				refreshTokenHandler(request, response);
			} else if (HttpMethod.DELETE.equals(request.getMethod())) {
				revokeAllTokensHandler(request, response);
			}
			return;
		} else if (RestRequestMatcher.INSTANCE_STRICT.matches(request) &&
				SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				// validate token if present
				JWTAuthentication jwt = getJWTfromRequest(request, response);
				if (jwt != null) {
					Authentication auth = authenticationManager.authenticate(jwt);
					// success!
					SecurityContextHolder.getContext().setAuthentication(auth);
				} else {
					response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
				}
			} catch (AuthenticationException authenticationException) {
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
				logger.debug("AuthenticationManager rejected JWT Authentication.", authenticationException);
			}
		}

		chain.doFilter(request, response);
	}

	@SuppressWarnings("unchecked")
	private boolean newTokenHandler(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		Response res = RestUtils.getEntity(request.getInputStream(), Map.class);
		if (res.getStatusInfo() != Response.Status.OK) {
			RestUtils.returnStatusResponse(response, res.getStatus(), res.getEntity().toString());
			return false;
		}
		Map<String, Object> entity = (Map<String, Object>) res.getEntity();
		String provider = (String) entity.get("provider");
		String appid = (String) entity.get(Config._APPID);
		String token = (String) entity.get("token");

		if (provider != null && appid != null && token != null) {
			// don't allow clients to create users on root app unless this is explicitly configured
			if (!App.isRoot(appid) || Config.getConfigBoolean("clients_can_access_root_app", false)) {
				App app = Para.getDAO().read(App.id(appid));
				if (app != null) {
					response.setHeader("APP_ID", app.getAppIdentifier());
					try {
						UserAuthentication userAuth = getOrCreateUser(app, provider, token);
						User user = SecurityUtils.getAuthenticatedUser(userAuth);
						if (user != null && user.getActive()) {
						    // 检查当前用户是否已经登录（查询metaLogin登录记录表是否存在有效的数据）
                            // 存在时则先将上次的登录token失效，更新登录记录表数据失效时间，然后进行登录
                            // isMetaLogin参数用于开发人员调试问题使用，isMetaLogin=false时不会生成登录日志，不影响客户账户的使用
                            Boolean isMetaLogin = (Boolean) entity.getOrDefault("isMetaLogin", true);

                            SignedJWT newJWT;
                            if (isMetaLogin == null || isMetaLogin) {
                                String agent = getClientAgent(request);
                                newJWT = getJWToken(app, user, agent);
                            } else {
                                newJWT = SecurityUtils.generateJWToken(user, app);
                            }

                            if (newJWT != null) {
                                succesHandler(response, user, newJWT);
                                return true;
                            }
                        } else {
                            String message = "Failed to authenticate user with '" + provider + "'. Check if user is active.";

                            RestUtils.returnStatusResponse( response, HttpServletResponse.SC_BAD_REQUEST,message );
                            return false;
                        }
					} catch (Exception e) {
						RestUtils.returnStatusResponse( response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
						return false;

					}
				} else {
					RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
							"User belongs to an app that does not exist.");
					return false;
				}
			} else {
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_FORBIDDEN,
							"Can't authenticate user with app '" + appid + "' using provider '" + provider + "'. "
									+ "Reason: clients aren't allowed to access root app.");
					return false;
			}
		}
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
				"Some of the required query parameters 'provider', 'appid', 'token', are missing.");
		return false;
	}

    private SignedJWT getJWToken(App app, User user, String agent) throws ParseException {
        // 查询登录记录
		List<ParaObject> metaLogins = getMetaLogins(app.getAppid(), user.getId(), agent, 0);
		if (metaLogins != null && !metaLogins.isEmpty()) {
            // 过滤出未失效的登录记录
            metaLogins = metaLogins.stream().filter(t -> {
                long failTime = ParaObjectUtils.getPropertyAsLong(t, "failTime");
                return failTime == 0;
            }).collect(Collectors.toList());

            metaLogins.forEach(t -> ParaObjectUtils.setProperty(t, "failTime", System.currentTimeMillis()));
			CoreUtils.getInstance().getDao().updateAll(metaLogins);
        }

        // issue token
        SignedJWT signedJWT = SecurityUtils.generateJWToken(user, app);
        if (signedJWT != null) {
            // 保存token登录记录
            createMetaLogin(user.getId(), user.getName(), agent, signedJWT.getJWTClaimsSet().getNotBeforeTime().getTime());
        }
        return signedJWT;
    }

    private String getClientAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        boolean isMobileClient = ParaObjectUtils.isMobileClient(userAgent);
        String agent = isMobileClient ? "Mobile" : "PC";
        if (isMobileClient) {
            boolean isWechat = ParaObjectUtils.isMicroMessenger(userAgent);
            agent = isWechat ? "MicroMessenger" : agent;
        }
        return agent;
    }

	private List<ParaObject> getMetaLogins(String appid, String userId, String agent, long loginTime) {
		HashMap<String, String> map = new HashMap<>();
		map.put("active", "true");
		map.put("userId", userId);
		if (StringUtils.isNotBlank(agent)) {
            map.put("clientId", agent);
        }
        if (loginTime > 0) {
            map.put("loginTime", String.valueOf(loginTime));
        }
		return CoreUtils.getInstance().getDao().findTerms(appid, "metaLogin", map, true, new Pager(1, 10));
	}

	private void createMetaLogin(String userId, String userName, String agent, long loginTime) throws ParseException {
        Map<String, Object> loginMap = new HashMap<>();
        loginMap.put("type", "metaLogin");
        loginMap.put("userId", userId);
        loginMap.put("userName", userName);
        loginMap.put("clientId", agent);
        loginMap.put("loginTime", loginTime);
        ParaObject metaLogin = ParaObjectUtils.setAnnotatedFields(loginMap);
        metaLogin.setId(Utils.getNewId());
        metaLogin.create();
    }

    private boolean validateToken(HttpServletRequest request, SignedJWT jwt, String appid, String userId) throws ParseException {
        JWTClaimsSet claimsSet = jwt.getJWTClaimsSet();
        Date notBeforeTime = claimsSet.getNotBeforeTime();

//        String agent = getClientAgent(request);
//        HashMap<String, String> map = new HashMap<>();
//        map.put("active", "true");
//        map.put("userId", userId);
//        map.put("loginTime", String.valueOf(notBeforeTime.getTime()));
////        map.put("clientId", agent);
//        List<ParaObject> metaLogins = CoreUtils.getInstance().getDao().findTerms(appid, "metaLogin", map, true);
        List<ParaObject> metaLogins = getMetaLogins(appid, userId, null, notBeforeTime.getTime());
        if (metaLogins != null && !metaLogins.isEmpty()) {
            for (ParaObject metaLogin : metaLogins) {
//                long loginTime = ParaObjectUtils.getPropertyAsLong(metaLogin, "loginTime");
//                if (notBeforeTime.getTime() == loginTime) {
                long failTime = ParaObjectUtils.getPropertyAsLong(metaLogin, "failTime");
                if (failTime == 0) {
                    return true;
                }
//                }
            }
        }
        return false;
    }

    private boolean refreshTokenHandler(HttpServletRequest request, HttpServletResponse response) {
		JWTAuthentication jwtAuth = getJWTfromRequest(request, response);
		if (jwtAuth != null) {
			try {
				User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
				if (user != null) {
					// check and reissue token
					jwtAuth = (JWTAuthentication) authenticationManager.authenticate(jwtAuth);
					if (jwtAuth != null && jwtAuth.getApp() != null) {
						response.setHeader("APP_ID", jwtAuth.getApp().getAppIdentifier());
						SignedJWT newToken = SecurityUtils.generateJWToken(user, jwtAuth.getApp());
						if (newToken != null) {
							succesHandler(response, user, newToken);
							return true;
						}
					}
				}
			} catch (Exception ex) {
				logger.debug(ex);
			}
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "User must reauthenticate.");
		return false;
	}

	private boolean revokeAllTokensHandler(HttpServletRequest request, HttpServletResponse response) {
		JWTAuthentication jwtAuth = getJWTfromRequest(request, response);
		if (jwtAuth != null) {
			try {
				User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
				if (user != null) {
					jwtAuth = (JWTAuthentication) authenticationManager.authenticate(jwtAuth);
					if (jwtAuth != null && jwtAuth.getApp() != null) {

                        JWTClaimsSet claimsSet = jwtAuth.getJwt().getJWTClaimsSet();
                        Date notBeforeTime = claimsSet.getNotBeforeTime();

						String agent = getClientAgent(request);
						List<ParaObject> metaLogins = getMetaLogins(jwtAuth.getApp().getAppid(), user.getId(), agent, 0);
                        if (metaLogins != null && !metaLogins.isEmpty()) {
                            metaLogins = metaLogins.stream().filter(t -> {
                                long failTime = ParaObjectUtils.getPropertyAsLong(t, "failTime");
                                return failTime == 0;
                            }).collect(Collectors.toList());

                            metaLogins.forEach(t -> ParaObjectUtils.setProperty(t, "failTime", System.currentTimeMillis()));
			                CoreUtils.getInstance().getDao().updateAll(metaLogins);
                        }

//                        user.resetTokenSecret();
						response.setHeader("APP_ID", jwtAuth.getApp().getAppIdentifier());
//						CoreUtils.getInstance().overwrite(jwtAuth.getApp().getAppIdentifier(), user);
						RestUtils.returnStatusResponse(response, HttpServletResponse.SC_OK,
								Utils.formatMessage("All tokens revoked for user {0}!", user.getId()));
						return true;
					}
				}
			} catch (Exception ex) {
				logger.debug(ex);
			}
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
				"Invalid or expired token.");
		return false;
	}

	private void succesHandler(HttpServletResponse response, User user, final SignedJWT token) {
		if (user != null && token != null) {
			Map<String, Object> result = new HashMap<>();
			try {
				HashMap<String, Object> jwt = new HashMap<>();
				jwt.put("access_token", token.serialize());
				jwt.put("refresh", token.getJWTClaimsSet().getLongClaim("refresh"));
				jwt.put("expires", token.getJWTClaimsSet().getExpirationTime().getTime());
				result.put("jwt", jwt);
				result.put("user", user);
				response.setHeader("Authorization", "Bearer" + token.serialize());
			} catch (ParseException ex) {
				logger.info("Unable to parse JWT.", ex);
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Bad token.");
			}
			RestUtils.returnObjectResponse(response, result);
		} else {
			RestUtils.returnStatusResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Null token.");
		}
	}

	private JWTAuthentication getJWTfromRequest(HttpServletRequest request, HttpServletResponse response) {
		String token = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (token == null) {
			token = request.getParameter(HttpHeaders.AUTHORIZATION);
		}
		if (!StringUtils.isBlank(token) && token.contains("Bearer")) {
			try {
				SignedJWT jwt = SignedJWT.parse(token.substring(6).trim());
				String userid = jwt.getJWTClaimsSet().getSubject();
				String appid = (String) jwt.getJWTClaimsSet().getClaim(Config._APPID);
				App app = Para.getDAO().read(App.id(appid));
				if (app != null) {
					response.setHeader("APP_ID", app.getAppIdentifier());
					User user = Para.getDAO().read(app.getAppIdentifier(), userid);
					if (user != null) {
					    //2018-10-20 zhouzz 查询当前jwt对应的登录记录（metaLogin）
                        if (!validateToken(request, jwt, app.getId(), user.getId())) return null;

                        // standard user JWT auth, restricted access through resource permissions
						SecurityUtils.setTenantInfo(user);
						return new JWTAuthentication(new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(app);
					} else {
						// "super token" - subject is authenticated as app, full access
						return new JWTAuthentication(null).withJWT(jwt).withApp(app);
					}
				}
			} catch (ParseException e) {
				logger.debug("Unable to parse JWT.", e);
			}
		}
		return null;
	}

    private UserAuthentication getOrCreateUser(App app, String identityProvider, String accessToken)
			throws IOException {
		if ("password".equalsIgnoreCase(identityProvider)) {
			return passwordAuth.getOrCreateUser(app, accessToken);
//		} else if ("wechat".equalsIgnoreCase(identityProvider)) {
//			// 微信登录
//			return wechatAuth.getOrCreateUser(app, accessToken);
		} else if ("verificationcode".equalsIgnoreCase(identityProvider)) {
			// 验证码登陆
			return verificationCodeAuth.getOrCreateUser(app, accessToken);
		} else if ("facebook".equalsIgnoreCase(identityProvider)) {
			return facebookAuth.getOrCreateUser(app, accessToken);
		} else if ("google".equalsIgnoreCase(identityProvider)) {
			return googleAuth.getOrCreateUser(app, accessToken);
		} else if ("github".equalsIgnoreCase(identityProvider)) {
			return githubAuth.getOrCreateUser(app, accessToken);
		} else if ("linkedin".equalsIgnoreCase(identityProvider)) {
			return linkedinAuth.getOrCreateUser(app, accessToken);
		} else if ("twitter".equalsIgnoreCase(identityProvider)) {
			return twitterAuth.getOrCreateUser(app, accessToken);
		} else if ("microsoft".equalsIgnoreCase(identityProvider)) {
			return microsoftAuth.getOrCreateUser(app, accessToken);
		} else if ("oauth2".equalsIgnoreCase(identityProvider)) {
			return oauth2Auth.getOrCreateUser(app, accessToken);
		} else if ("ldap".equalsIgnoreCase(identityProvider)) {
			return ldapAuth.getOrCreateUser(app, accessToken);
		}
		return null;
	}

	private void setFilterProcessesUrl(String filterProcessesUrl) {
		this.authenticationRequestMatcher = new AntPathRequestMatcher(filterProcessesUrl);
	}

	/**
	 * @return auth manager
	 */
	protected AuthenticationManager getAuthenticationManager() {
		return authenticationManager;
	}

	/**
	 * @param authenticationManager auth manager
	 */
	public void setAuthenticationManager(AuthenticationManager authenticationManager) {
		this.authenticationManager = authenticationManager;
	}

	@Override
	public void afterPropertiesSet() {
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
	}

	/**
	 * @return auth filter
	 */
	public FacebookAuthFilter getFacebookAuth() {
		return facebookAuth;
	}

	/**
	 * @param facebookAuth auth filter
	 */
	@Inject
	public void setFacebookAuth(FacebookAuthFilter facebookAuth) {
		this.facebookAuth = facebookAuth;
	}

	/**
	 * @return auth filter
	 */
	public GoogleAuthFilter getGoogleAuth() {
		return googleAuth;
	}

	/**
	 * @param googleAuth auth filter
	 */
	@Inject
	public void setGoogleAuth(GoogleAuthFilter googleAuth) {
		this.googleAuth = googleAuth;
	}

	/**
	 * @return auth filter
	 */
	public GitHubAuthFilter getGithubAuth() {
		return githubAuth;
	}

	/**
	 * @param githubAuth auth filter
	 */
	@Inject
	public void setGithubAuth(GitHubAuthFilter githubAuth) {
		this.githubAuth = githubAuth;
	}

	/**
	 * @return auth filter
	 */
	public LinkedInAuthFilter getLinkedinAuth() {
		return linkedinAuth;
	}

	/**
	 * @param linkedinAuth auth filter
	 */
	@Inject
	public void setLinkedinAuth(LinkedInAuthFilter linkedinAuth) {
		this.linkedinAuth = linkedinAuth;
	}

	/**
	 * @return auth filter
	 */
	public TwitterAuthFilter getTwitterAuth() {
		return twitterAuth;
	}

	/**
	 * @param twitterAuth auth filter
	 */
	@Inject
	public void setTwitterAuth(TwitterAuthFilter twitterAuth) {
		this.twitterAuth = twitterAuth;
	}

	/**
	 * @return auth filter
	 */
	public MicrosoftAuthFilter getMicrosoftAuth() {
		return microsoftAuth;
	}

	/**
	 * @param microsoftAuth auth filter
	 */
	@Inject
	public void setMicrosoftAuth(MicrosoftAuthFilter microsoftAuth) {
		this.microsoftAuth = microsoftAuth;
	}

	/**
	 * @return auth filter
	 */
	public GenericOAuth2Filter getGenericOAuth2Auth() {
		return oauth2Auth;
	}

	/**
	 * @param oauthAuth auth filter
	 */
	@Inject
	public void setGenericOAuth2Auth(GenericOAuth2Filter oauthAuth) {
		this.oauth2Auth = oauthAuth;
	}

	/**
	 * @return auth filter
	 */
	public LdapAuthFilter getLdapAuth() {
		return ldapAuth;
	}

	/**
	 * @param ldapAuth auth filter
	 */
	public void setLdapAuth(LdapAuthFilter ldapAuth) {
		this.ldapAuth = ldapAuth;
	}

	/**
	 * @return auth filter
	 */
	public PasswordAuthFilter getPasswordAuth() {
		return passwordAuth;
	}

	/**
	 * @param passwordAuth auth filter
	 */
	@Inject
	public void setPasswordAuth(PasswordAuthFilter passwordAuth) {
		this.passwordAuth = passwordAuth;
	}

	/**
	 * @return auth filter
	 */
	public WechatAuthFilter getWechatAuth() {
		return wechatAuth;
	}

	/**
	 * @param wechatAuth auth filter
	 */
	@Inject
	public void setWechatAuth(WechatAuthFilter wechatAuth) {
		this.wechatAuth = wechatAuth;
	}

	public VerificationCodeAuthFilter getVerificationCodeAuth() {
		return verificationCodeAuth;
	}

	public void setVerificationCodeAuth(VerificationCodeAuthFilter verificationCodeAuth) {
		this.verificationCodeAuth = verificationCodeAuth;
	}
}
