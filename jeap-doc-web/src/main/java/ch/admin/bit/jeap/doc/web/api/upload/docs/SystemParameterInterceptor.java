package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks for the system of a system's documentation before the role is checked.
 * <p>
 * <b>The role is the system's</b>, so an upload or a dry run of a system's documentation that names no system
 * could only be answered 403 by the role check - which says nothing about the parameter the workflow forgot. The
 * parameter is optional on the endpoints only because a static site's tree names no system, so this check runs
 * before the role is: a forgotten {@code system} is answered 400, naming it, as it was before static sites.
 * <p>
 * Reading what became of an upload is left alone: without a system it is a question about a site's upload.
 */
class SystemParameterInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String type = request.getParameter("type");
        if (HttpMethod.GET.matches(request.getMethod()) || type == null) {
            // A missing type is the binding's to report, as for every required parameter.
            return true;
        }
        if (DocumentationTypeDto.fromParameterValue(type) != DocumentationTypeDto.SITE_DOCS
            && !StringUtils.hasText(request.getParameter("system"))) {
            throw InvalidUploadException.missing("system",
                    "to know whose documentation it is, as the role it is uploaded with is granted for a system");
        }
        return true;
    }
}
