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
import com.semanticcms.core.model.ElementContext;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.pages.CaptureLevel;
import com.semanticcms.core.pages.local.CurrentCaptureLevel;
import com.semanticcms.core.pages.local.CurrentNode;
import com.semanticcms.core.pages.local.CurrentPage;
import com.semanticcms.core.renderer.html.LinkRenderer;
import com.semanticcms.core.renderer.html.PageIndex;
import com.semanticcms.news.model.News;
import com.semanticcms.section.renderer.html.SectionHtmlRenderer;
import java.io.IOException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.NotImplementedException;

final public class NewsHtmlRenderer {

	// TODO: This should be in the servlet implementation, not in the renderer.  May be able to simplify dependencies.
	public static void doBodyImpl(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
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
			final BookRef targetBookRef = targetPageRef.getBookRef();
			// Add page links
			// TODO: Allow view="news" somehow in the "what-links-here", as-is news elements are hidden and don't show.
			//       Or show news elements but they link to "news" view (when present).
			//       Would have to check all views for what-links-here, but it could work?
			news.addPageLink(targetPageRef);
			String newsElement = news.getElement();
			String newsTitle = news.getTitle();
			if(newsElement == null || newsTitle == null) {
				// The target page will be null when in a missing book
				Page targetPage;
				if(!SemanticCMS.getInstance(servletContext).getBook(targetBookRef).isAccessible()) {
					targetPage = null;
				} else if(
					// Short-cut for element already added above within current page
					targetPageRef.equals(currentPageRef)
					&& (
						newsElement == null
						|| currentPage.getElementsById().containsKey(newsElement)
					)
				) {
					targetPage = currentPage;
				} else {
					// Capture required, even if capturing self
					// TODO: This would cause unbound recursion and stack overflow at this time, there may be a complicate workaround when needed, such as not running this element on the recursive capture
					if(targetPageRef.equals(currentPageRef)) throw new NotImplementedException("Forward reference to element in same page not supported yet");
					targetPage = CapturePage.capturePage(
						servletContext,
						request,
						response,
						targetPageRef,
						newsElement == null ? CaptureLevel.PAGE : CaptureLevel.META
					);
				}
				// Find the optional target element, may remain null when in missing book
				Element targetElement;
				if(newsElement == null) {
					// TODO: Locating the default targetElement based on parent element should be done after page element IDs
					//       are generated and before the News element is frozen.  As-is, the default targetElement is only
					//       set by the "renderer" layer, which may or may not happen in future releases when renderers are
					//       a distinct different layer than the model and capture.
					if(news.getBook() == null && news.getTargetPage() == null) {
						Element parentElem = news.getParentElement();
						if(parentElem != null) {
							// Default to parent of current element
							targetElement = parentElem;
							newsElement = targetElement.getId();
							news.setElement(newsElement);
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
						targetElement = targetPage.getElementsById().get(newsElement);
						if(targetElement == null) throw new ServletException("Element not found in target page: " + newsElement);
						if(targetPage.getGeneratedIds().contains(newsElement)) throw new ServletException("Not allowed to link to a generated element id, set an explicit id on the target element: " + newsElement);
					} else {
						targetElement = null;
					}
				}
				// Find the title if not set
				if(newsTitle == null) {
					String title;
					if(newsElement != null) {
						if(targetElement == null) {
							// Element in missing book
							title = LinkRenderer.getBrokenPath(targetPageRef, newsElement);
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
					newsTitle = title;
					news.setTitle(newsTitle);
				}
			}
			// Set book and targetPage always, since news is used from views on other pages
			// These must be set after finding the element, since book/page being null affects which element, if any, is used
			news.setDomain(targetBookRef.getDomain());
			news.setBook(targetBookRef.getPath());
			news.setTargetPage(targetPageRef.getPath().toString());
		}
	}

	public static void writeNewsImpl(
		HttpServletRequest request,
		Html html,
		ElementContext context,
		News news,
		PageIndex pageIndex
	) throws ServletException, IOException {
		Page page = news.getPage();
		// Write table of contents before this, if needed on the page
		try {
			SectionHtmlRenderer.writeToc(request, html, context, page);
		} catch(Error | RuntimeException | ServletException | IOException e) {
			throw e;
		} catch(Exception e) {
			throw new ServletException(e);
		}
		// Write an empty div so links to this news ID work
		String refId = PageIndex.getRefIdInPage(request, page, news.getId());
		html.out.append("<div class=\"semanticcms-news-anchor\" id=\"");
		encodeTextInXhtmlAttribute(refId, html.out);
		html.out.append("\"></div>");
		// TODO: Should we show the news entry here when no news view is active?
		// TODO: Hide from tree views, or leave but link to "news" view when news view is active?
	}

	/**
	 * Make no instances.
	 */
	private NewsHtmlRenderer() {
	}
}
