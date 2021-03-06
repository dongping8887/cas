package org.apereo.cas.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.ticket.Ticket;
import org.apereo.cas.util.Pac4jUtils;
import org.apereo.cas.web.pac4j.DelegatedSessionCookieManager;
import org.jasig.cas.client.util.URIBuilder;
import org.pac4j.core.client.Clients;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.context.J2EContext;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.redirect.RedirectAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * This is {@link DelegatedClientNavigationController}.
 *
 * @author Misagh Moayyed
 * @since 5.3.0
 */
@Controller("delegatedClientNavigationController")
@RequestMapping
@Slf4j
@RequiredArgsConstructor
public class DelegatedClientNavigationController {
    /**
     * Endpoint path controlled by this controller to make the redirect.
     */
    public static final String ENDPOINT_REDIRECT = "/clientredirect";

    private final Clients clients;
    private final DelegatedClientWebflowManager delegatedClientWebflowManager;
    private final DelegatedSessionCookieManager delegatedSessionCookieManager;

    /**
     * Redirect to provider. Receive the client name from the request and then try to determine and build the endpoint url
     * for the redirection. The redirection data/url must contain a delegated client ticket id so that the request be can
     * restored on the trip back. SAML clients use the relay-state session attribute while others use request parameters.
     *
     * @param request  the request
     * @param response the response
     * @return the view
     */
    @GetMapping(ENDPOINT_REDIRECT)
    public View redirectToProvider(final HttpServletRequest request, final HttpServletResponse response) {
        final String clientName = request.getParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER);
        try {
            final IndirectClient client = (IndirectClient<Credentials, CommonProfile>) this.clients.findClient(clientName);
            final J2EContext webContext = Pac4jUtils.getPac4jJ2EContext(request, response);
            final Ticket ticket = delegatedClientWebflowManager.store(webContext, client);

            final View result;
            final RedirectAction action = client.getRedirectAction(webContext);
            if (RedirectAction.RedirectType.SUCCESS.equals(action.getType())) {
                result = new DynamicHtmlView(action.getContent());
            } else {
                final URIBuilder builder = new URIBuilder(action.getLocation());
                final String url = builder.toString();
                LOGGER.debug("Redirecting client [{}] to [{}] based on identifier [{}]", client.getName(), url, ticket.getId());
                result = new RedirectView(url);
            }
            this.delegatedSessionCookieManager.store(webContext);
            return result;
        } catch (final HttpAction e) {
            if (e.getCode() == HttpStatus.UNAUTHORIZED.value()) {
                LOGGER.debug("Authentication request was denied from the provider [{}]", clientName);
            } else {
                LOGGER.warn(e.getMessage(), e);
            }
            throw new UnauthorizedServiceException(e.getMessage(), e);
        }
    }


    @RequiredArgsConstructor
    private static class DynamicHtmlView implements View {
        private final String html;

        public String getContentType() {
            return MediaType.TEXT_HTML_VALUE;
        }

        public void render(final Map<String, ?> model, final HttpServletRequest request, final HttpServletResponse response) throws Exception {
            response.setContentType(this.getContentType());
            FileCopyUtils.copy(this.html, response.getWriter());
        }
    }
}
