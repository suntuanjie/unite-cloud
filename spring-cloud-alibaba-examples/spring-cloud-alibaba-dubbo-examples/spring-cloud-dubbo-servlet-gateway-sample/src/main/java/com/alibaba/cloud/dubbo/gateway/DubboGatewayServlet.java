package com.alibaba.cloud.dubbo.gateway;

import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.service.GenericService;

import com.alibaba.cloud.dubbo.http.MutableHttpServerRequest;
import com.alibaba.cloud.dubbo.metadata.DubboRestServiceMetadata;
import com.alibaba.cloud.dubbo.metadata.RequestMetadata;
import com.alibaba.cloud.dubbo.metadata.RestMethodMetadata;
import com.alibaba.cloud.dubbo.metadata.repository.DubboServiceMetadataRepository;
import com.alibaba.cloud.dubbo.service.DubboGenericServiceExecutionContext;
import com.alibaba.cloud.dubbo.service.DubboGenericServiceExecutionContextFactory;
import com.alibaba.cloud.dubbo.service.DubboGenericServiceFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.HttpServletBean;
import org.springframework.web.util.UriComponents;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBetween;
import static org.springframework.web.util.UriComponentsBuilder.fromUriString;

@WebServlet(urlPatterns = "/dsc/*")
public class DubboGatewayServlet extends HttpServletBean {

	private final DubboServiceMetadataRepository repository;

	private final DubboGenericServiceFactory serviceFactory;

	private final DubboGenericServiceExecutionContextFactory contextFactory;

	private final PathMatcher pathMatcher = new AntPathMatcher();

	private final Map<String, Object> dubboTranslatedAttributes = new HashMap<>();

	public DubboGatewayServlet(DubboServiceMetadataRepository repository,
			DubboGenericServiceFactory serviceFactory,
			DubboGenericServiceExecutionContextFactory contextFactory) {
		this.repository = repository;
		this.serviceFactory = serviceFactory;
		this.contextFactory = contextFactory;
		dubboTranslatedAttributes.put("protocol", "dubbo");
		dubboTranslatedAttributes.put("cluster", "failover");
	}

	private String resolveServiceName(HttpServletRequest request) {

		// /g/{app-name}/{rest-path}
		String requestURI = request.getRequestURI();
		// /g/
		String servletPath = request.getServletPath();

		String part = substringAfter(requestURI, servletPath);

		String serviceName = substringBetween(part, "/", "/");

		return serviceName;
	}

	@Override
	public void service(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {

		String serviceName = resolveServiceName(request);

		String restPath = substringAfter(request.getRequestURI(), serviceName);

		// ????????? serviceName ??? REST ???????????????
		repository.initializeMetadata(serviceName);
		// ??? HttpServletRequest ????????? RequestMetadata
		RequestMetadata clientMetadata = buildRequestMetadata(request, restPath);

		DubboRestServiceMetadata dubboRestServiceMetadata = repository.get(serviceName,
				clientMetadata);

		if (dubboRestServiceMetadata == null) {
			// if DubboServiceMetadata is not found, executes next
			throw new ServletException("DubboServiceMetadata can't be found!");
		}

		RestMethodMetadata dubboRestMethodMetadata = dubboRestServiceMetadata
				.getRestMethodMetadata();

		GenericService genericService = serviceFactory.create(dubboRestServiceMetadata,
				dubboTranslatedAttributes);

		// TODO: Get the Request Body from HttpServletRequest
		byte[] body = getRequestBody(request);

		MutableHttpServerRequest httpServerRequest = new MutableHttpServerRequest(
				new HttpRequestAdapter(request), body);

		DubboGenericServiceExecutionContext context = contextFactory
				.create(dubboRestMethodMetadata, httpServerRequest);

		Object result = null;
		GenericException exception = null;

		try {
			result = genericService.$invoke(context.getMethodName(),
					context.getParameterTypes(), context.getParameters());
		}
		catch (GenericException e) {
			exception = e;
		}
		response.getWriter().println(result);
	}

	private byte[] getRequestBody(HttpServletRequest request) throws IOException {
		ServletInputStream inputStream = request.getInputStream();
		return StreamUtils.copyToByteArray(inputStream);
	}

	private static class HttpRequestAdapter implements HttpRequest {

		private final HttpServletRequest request;

		private HttpRequestAdapter(HttpServletRequest request) {
			this.request = request;
		}

		@Override
		public String getMethodValue() {
			return request.getMethod();
		}

		@Override
		public URI getURI() {
			try {
				return new URI(request.getRequestURL().toString() + "?"
						+ request.getQueryString());
			}
			catch (URISyntaxException e) {
				e.printStackTrace();
			}
			throw new RuntimeException();
		}

		@Override
		public HttpHeaders getHeaders() {
			return new HttpHeaders();
		}
	}

	private RequestMetadata buildRequestMetadata(HttpServletRequest request,
			String restPath) {
		UriComponents uriComponents = fromUriString(request.getRequestURI()).build(true);
		RequestMetadata requestMetadata = new RequestMetadata();
		requestMetadata.setPath(restPath);
		requestMetadata.setMethod(request.getMethod());
		requestMetadata.setParams(getParams(request));
		requestMetadata.setHeaders(getHeaders(request));
		return requestMetadata;
	}

	private Map<String, List<String>> getHeaders(HttpServletRequest request) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		Enumeration<String> headerNames = request.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			Enumeration<String> headerValues = request.getHeaders(headerName);
			map.put(headerName, Collections.list(headerValues));
		}
		return map;
	}

	private Map<String, List<String>> getParams(HttpServletRequest request) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			map.put(entry.getKey(), Arrays.asList(entry.getValue()));
		}
		return map;
	}
}
