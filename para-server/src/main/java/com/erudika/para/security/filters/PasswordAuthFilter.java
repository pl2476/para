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
package com.erudika.para.security.filters;

import com.erudika.para.Para;
import com.erudika.para.core.App;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.User;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.security.AuthenticatedUserDetails;
import com.erudika.para.security.SecurityUtils;
import com.erudika.para.security.UserAuthentication;
import com.erudika.para.utils.Config;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * A filter that handles simple authentication requests with email and password.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class PasswordAuthFilter extends AbstractAuthenticationProcessingFilter {

	private static final String PASSWORD = "password";
	private static final String EMAIL = "email";

	/**
	 * The default filter mapping.
	 */
	public static final String PASSWORD_ACTION = "password_auth";

	/**
	 * Default constructor.
	 * @param defaultFilterProcessesUrl the url of the filter
	 */
	public PasswordAuthFilter(String defaultFilterProcessesUrl) {
		super(defaultFilterProcessesUrl);
	}

	/**
	 * Handles an authentication request.
	 * @param request HTTP request
	 * @param response HTTP response
	 * @return an authentication object that contains the principal object if successful.
	 * @throws IOException ex
	 * @throws ServletException ex
	 */
	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		String requestURI = request.getRequestURI();
		UserAuthentication userAuth = null;
		User user = null;

		if (requestURI.endsWith(PASSWORD_ACTION)) {
			user = new User();
			user.setIdentifier(request.getParameter(EMAIL));
			user.setPassword(request.getParameter(PASSWORD));
			String appid = request.getParameter(Config._APPID);
			if (!App.isRoot(appid)) {
				App app = Para.getDAO().read(App.id(appid));
				if (app != null) {
					user.setAppid(app.getAppIdentifier());
				}
			}
			if (User.passwordMatches(user) && StringUtils.contains(user.getIdentifier(), "@")) {
				//success!
				user = User.readUserForIdentifier(user);
				userAuth = new UserAuthentication(new AuthenticatedUserDetails(user));
			}
		}
		return SecurityUtils.checkIfActive(userAuth, user, true);
	}

	private static boolean isPhone(String value) {
		if (value.length() != 11 || value.charAt(0) != '1') {
			return false;
		}

		for(int i = 1; i < 11; i++) {
			if (!Character.isDigit(value.charAt(0))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Authenticates or creates a {@link User} using an email and password.
	 * Access token must be in the format: "email:full_name:password" or "email::password_hash"
	 * @param app the app where the user will be created, use null for root app
	 * @param accessToken token in the format "email:full_name:password" or "email::password_hash"
	 * @return {@link UserAuthentication} object or null if something went wrong
	 */
	public UserAuthentication getOrCreateUser(App app, String accessToken) {
		UserAuthentication userAuth = null;
		User user = new User();
		if (accessToken != null && accessToken.contains(Config.SEPARATOR)) {
			String[] parts = accessToken.split(Config.SEPARATOR, 3);
			String email = parts[0];
			String name = parts[1];
			String pass = (parts.length > 2) ? parts[2] : "";

			String appid = (app == null) ? null : app.getAppIdentifier();
			User u = new User();
			u.setAppid(appid);
			u.setIdentifier(email);
			u.setPassword(pass);
			u.setEmail(email);

			// login by phone or loginId
			if (StringUtils.isNoneBlank(email) && StringUtils.indexOf(email, '@') < 0) {
				email = email.trim();
				HashMap<String, String> map = new HashMap<>();
				if (isPhone(email)) {
					map.put("phone", email);
				} else {
					map.put("loginId", email);
				}
				map.put("active", "true");
				List<Sysprop> muList = CoreUtils.getInstance().getDao().findTerms(app.getAppid(), "metaUser", map, true);
				if (muList != null && !muList.isEmpty()) {
					Sysprop mu = muList.get(0);
					map.clear();
					map.put("id", mu.getParentid());
					List<User> userList = CoreUtils.getInstance().getDao().findTerms(app.getAppid(), "user", map, true);
					if (userList != null && !userList.isEmpty()) {
						user = userList.get(0);
						u.setIdentifier(user.getIdentifier());
						u.setEmail(user.getEmail());
						if (User.passwordMatches(u)) {
							SecurityUtils.setTenantInfo(user, mu);
							userAuth = new UserAuthentication(new AuthenticatedUserDetails(user));
							return SecurityUtils.checkIfActive(userAuth, user, false);
						} else {
                            logger.error("User '" + email + "' password not match");
							throw new RuntimeException("账号或密码不正确，请重新输入!");

						}
					}
				} else {
                    logger.error("User '" + email + "' not found");
                    throw new RuntimeException("账号不存在!");

                }
			}

			// NOTE TO SELF:
			// do not overwrite 'u' here - overwrites the password hash!
			user = User.readUserForIdentifier(u);
			if (user == null && (Config.getConfigBoolean("security.allow_auto_register_users", false)
					|| email.equals(Config.ADMIN_IDENT))) {
				user = new User();
				user.setActive(Config.getConfigBoolean("security.allow_unverified_emails", false));
				user.setAppid(appid);
				user.setName(name);
				user.setIdentifier(email);
				user.setEmail(email);
				user.setPassword(pass);
				if (user.create() != null) {
					// allow temporary first-time login without verifying email address
					SecurityUtils.setTenantInfo(user);
					userAuth = new UserAuthentication(new AuthenticatedUserDetails(user));
				}
			} else if (user == null) {
				logger.error("User '" + email + "' not auto registered");
                throw new RuntimeException("账号不存在!");
//				return null;
			} else if (user.getActive() && User.passwordMatches(u)) {
				SecurityUtils.setTenantInfo(user);
				userAuth = new UserAuthentication(new AuthenticatedUserDetails(user));
			} else {
                logger.error("User '" + email + "' password not match");
                throw new RuntimeException("账号或密码不正确，请重新输入!");

            }
		}
		return SecurityUtils.checkIfActive(userAuth, user, false);
	}
}
