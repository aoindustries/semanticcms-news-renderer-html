/*
 * semanticcms-news-renderer-html - SemanticCMS newsfeeds rendered as HTML in a Servlet environment.
 * Copyright (C) 2016, 2017, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of semanticcms-news-renderer-html.
 *
 * semanticcms-news-renderer-html is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * semanticcms-news-renderer-html is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with semanticcms-news-renderer-html.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.semanticcms.news.renderer.html;

import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.encodeTextInXhtmlAttribute;
import com.aoindustries.html.Html;
import com.semanticcms.core.controller.CapturePage;
import com.semanticcms.core.controller.PageRefResolver;
import com.semanticcms.core.controller.SemanticCMS;
import com.semanticcms.core.model.BookRef;
import com.semanticcms.core.model.Element;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.pages.CaptureLevel;
import com.semanticcms.core.pages.local.CurrentCaptureLevel;
import com.semanticcms.core.pages.local.CurrentNode;
import com.semanticcms.core.pages.local.CurrentPage;
import com.semanticcms.core.renderer.html.LinkRenderer;
import com.semanticcms.core.renderer.html.PageIndex;
import com.semanticcms.news.model.News;
import java.io.IOException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class NewsHtmlRenderer {

	@SuppressWarnings("deprecation")
	public static void writeNewsImpl(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Html html,
		News news
	) throws ServletException, IOException {
		// Get the current capture state
		final CaptureLevel captureLevel = CurrentCaptureLevel.getCaptureLevel(request);
		if(captureLevel.compareTo(CaptureLevel.META) >= 0) {
			assert CurrentNode.getCurrentNode(request) == news;
			final Page currentPage = CurrentPage.getCurrentPage(request);
			if(currentPage == null) throw new ServletException("news must be nested within a page");

			if(news.getDomain() != null && news.getBook() == null) {
				throw new ServletException("book required when domain provided.");
			}

			// Find the target page
			final PageRef currentPageRef = currentPage.getPageRef();
			final PageRef targetPageRef;
			if(news.getBook() == null) {
				assert news.getDomain() == null;
				if(news.getTargetPage() == null) {
					targetPageRef = currentPageRef;
				} else {
					targetPageRef = PageRefResolver.getPageRef(
						servletContext,
						request,
						null,
						null,
						news.getTargetPage()
					);
				}
			} else {
				if(news.getTargetPage() == null) throw new ServletException("page required when book provided.");
				targetPageRef = PageRefResolver.getPageRef(
					servletContext,
					request,
					news.getDomain(),
					news.getBook(),
					news.getTargetPage()
				);
			}
			// Add page links
			// TODO: Allow view="news" somehow in the "what-links-here", as-is news elements are hidden and don't show.
			//       Or show news elements but they link to "news" view (when present).
			//       Would have to check all views for what-links-here, but it could work?
			news.addPageLink(targetPageRef);
			// The target page will be null when in a missing book
			final BookRef targetBookRef = targetPageRef.getBookRef();
			Page targetPage;
			if(!SemanticCMS.getInstance(servletContext).getBook(targetBookRef).isAccessible()) {
				targetPage = null;
			} else if(
				// Short-cut for element already added above within current page
				targetPageRef.equals(currentPageRef)
				&& (
					news.getElement()==null
					|| currentPage.getElementsById().containsKey(news.getElement())
				)
			) {
				targetPage = currentPage;
			} else {
				// Capture required, even if capturing self
				// TODO: This would cause unbound recursion and stack overflow at this time, there may be a complicate workaround when needed, such as not running this element on the recursive capture
				if(targetPageRef.equals(currentPageRef)) throw new com.aoindustries.lang.NotImplementedException("Forward reference to element in same page not supported yet");
				targetPage = CapturePage.capturePage(
					servletContext,
					request,
					response,
					targetPageRef,
					news.getElement()==null ? CaptureLevel.PAGE : CaptureLevel.META
				);
			}
			// Find the optional target element, may remain null when in missing book
			Element targetElement;
			if(news.getElement() == null) {
				if(news.getBook() == null && news.getTargetPage() == null) {
					Element parentElem = news.getParentElement();
					if(parentElem != null) {
						// Default to parent of current element
						targetElement = parentElem;
						news.setElement(targetElement.getId());
					} else {
						// No current element
						targetElement = null;
					}
				} else {
					// No element since book and/or page provided
					targetElement = null;
				}
			} else {
				// Find the element
				if(targetPage != null) {
					targetElement = targetPage.getElementsById().get(news.getElement());
					if(targetElement == null) throw new ServletException("Element not found in target page: " + news.getElement());
					if(targetPage.getGeneratedIds().contains(news.getElement())) throw new ServletException("Not allowed to link to a generated element id, set an explicit id on the target element: " + news.getElement());
				} else {
					targetElement = null;
				}
			}
			// Set book and targetPage always, since news is used from views on other pages
			// These must be set after finding the element, since book/page being null affects which element, if any, is used
			news.setDomain(targetBookRef.getDomain());
			news.setBook(targetBookRef.getPath());
			news.setTargetPage(targetPageRef.getPath().toString());
			// Find the title if not set
			if(news.getTitle() == null) {
				String title;
				if(news.getElement() != null) {
					if(targetElement == null) {
						// Element in missing book
						title = LinkRenderer.getBrokenPath(targetPageRef, news.getElement());
					} else {
						title = targetElement.getLabel();
						if(title == null || title.isEmpty()) throw new IllegalStateException("No label from targetElement: " + targetElement);
					}
				} else {
					if(targetPage == null) {
						// Page in missing book
						title = LinkRenderer.getBrokenPath(targetPageRef);
					} else {
						title = targetPage.getTitle();
					}
				}
				news.setTitle(title);
			}
			if(captureLevel == CaptureLevel.BODY) {
				// Write an empty div so links to this news ID work
				String refId = PageIndex.getRefIdInPage(request, currentPage, news.getId());
				html.out.append("<div class=\"semanticcms-news-anchor\" id=\"");
				encodeTextInXhtmlAttribute(refId, html.out);
				html.out.append("\"></div>");
				// TODO: Should we show the news entry here when no news view is active?
				// TODO: Hide from tree views, or leave but link to "news" view when news view is active?
			}
		}
	}

	/**
	 * Make no instances.
	 */
	private NewsHtmlRenderer() {
	}
}
