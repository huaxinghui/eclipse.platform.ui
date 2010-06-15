/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.e4.ui.workbench.addons.cleanupaddon;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.internal.workbench.swt.AbstractPartRenderer;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspectiveStack;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimBar;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuElement;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBar;
import org.eclipse.e4.ui.workbench.UIEvents;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Shell;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;

public class CleanupAddon {
	@Inject
	protected IEventBroker eventBroker;

	Shell limbo;

	private EventHandler childrenHandler = new EventHandler() {
		public void handleEvent(Event event) {
			Object changedObj = event.getProperty(UIEvents.EventTags.ELEMENT);
			String eventType = (String) event.getProperty(UIEvents.EventTags.TYPE);
			if (UIEvents.EventTypes.REMOVE.equals(eventType)) {
				final MElementContainer<?> container = (MElementContainer<?>) changedObj;
				if (container instanceof MApplication || container instanceof MWindow
						|| container instanceof MPerspectiveStack
						|| container instanceof MMenuElement) {
					return;
				}

				Display display = Display.getCurrent();

				// Stall the removal to handle cases where the container is only transiently empty
				if (display != null && !container.getTags().contains("EditorStack")) { //$NON-NLS-1$
					Display.getCurrent().asyncExec(new Runnable() {
						public void run() {
							// Remove it from the display if no visible children
							boolean hide = true;
							for (MUIElement child : container.getChildren()) {
								if (child.isToBeRendered()) {
									hide = false;
									break;
								}
							}
							if (hide) {
								container.setToBeRendered(false);
							}
							// Remove it from the model if it has no children at all
							if (container.getChildren().size() == 0) {
								MElementContainer<MUIElement> parent = container.getParent();
								if (container instanceof MToolBar) {
									// may be null if it's a part's toolbar
									if (parent != null) {
										parent.getChildren().remove(container);
									}
								} else {
									parent.getChildren().remove(container);
								}
							}
						}
					});
				}
			}
		}
	};

	private EventHandler visibilityChangeHandler = new EventHandler() {
		public void handleEvent(Event event) {
			MUIElement changedObj = (MUIElement) event.getProperty(UIEvents.EventTags.ELEMENT);
			if (changedObj instanceof MTrimBar)
				return;

			if (changedObj.getWidget() instanceof Shell) {
				((Shell) changedObj).setVisible(changedObj.isVisible());
			} else if (changedObj.getWidget() instanceof Control) {
				Control ctrl = (Control) changedObj.getWidget();
				MElementContainer<MUIElement> parent = changedObj.getParent();
				if (changedObj.isVisible()) {
					if (parent.getRenderer() != null) {
						Object myParent = ((AbstractPartRenderer) parent.getRenderer())
								.getUIContainer(changedObj);
						if (myParent instanceof Composite) {
							Composite parentComp = (Composite) myParent;
							ctrl.setParent(parentComp);

							Control prevControl = null;
							for (MUIElement childME : parent.getChildren()) {
								if (childME == changedObj)
									break;
								if (childME.getWidget() instanceof Control && childME.isVisible()) {
									prevControl = (Control) childME.getWidget();
								}
							}
							if (prevControl != null)
								ctrl.moveBelow(prevControl);
							else
								ctrl.moveAbove(null);
							ctrl.getShell().layout(new Control[] { ctrl }, SWT.DEFER);
						}

						// Check if the parent is visible
						if (!parent.isVisible())
							parent.setVisible(true);
					}
				} else {
					if (limbo == null) {
						limbo = new Shell(ctrl.getDisplay(), SWT.NONE);
						limbo.setLayout(new Layout() {
							@Override
							protected void layout(Composite composite, boolean flushCache) {
							}

							@Override
							protected Point computeSize(Composite composite, int wHint, int hHint,
									boolean flushCache) {
								return new Point(600, 400);
							}
						});
						limbo.setVisible(false);
					}

					// Reparent the control to 'limbo'
					Composite curParent = ctrl.getParent();
					ctrl.setParent(limbo);
					curParent.layout(true);
					if (curParent.getShell() != curParent)
						curParent.getShell().layout(new Control[] { curParent }, SWT.DEFER);

					// If there are no more 'visible' children then make the parent go away too
					boolean makeInvisible = true;
					for (MUIElement kid : parent.getChildren()) {
						if (kid.isToBeRendered() && kid.isVisible()) {
							makeInvisible = false;
							break;
						}
					}
					if (makeInvisible)
						parent.setVisible(false);
				}
			}
		}
	};

	private EventHandler renderingChangeHandler = new EventHandler() {
		public void handleEvent(Event event) {
			MUIElement changedObj = (MUIElement) event.getProperty(UIEvents.EventTags.ELEMENT);
			MElementContainer<MUIElement> container = null;
			if (changedObj.getCurSharedRef() != null)
				container = changedObj.getCurSharedRef().getParent();
			else
				container = changedObj.getParent();

			// this can happen for shared parts that aren't attached to any placeholders
			if (container == null) {
				return;
			}

			// Don't mess with editor stacks (for now)
			MUIElement containerElement = container;
			if (containerElement.getTags().contains("EditorStack")
					|| containerElement instanceof MWindow
					|| containerElement instanceof MPerspectiveStack) //$NON-NLS-1$
				return;

			Boolean toBeRendered = (Boolean) event.getProperty(UIEvents.EventTags.NEW_VALUE);
			if (toBeRendered) {
				// Bring the container back if one of its children goes visible
				if (!container.isToBeRendered())
					container.setToBeRendered(true);
			} else {
				int visCount = 0;
				for (MUIElement element : container.getChildren()) {
					if (element.isToBeRendered())
						visCount++;
				}

				// Remove stacks with no visible children from the display (but not the
				// model)
				if (visCount == 0) {
					container.setToBeRendered(false);
				}
			}
		}
	};

	@PostConstruct
	void init(IEclipseContext context) {
		eventBroker.subscribe(UIEvents.buildTopic(UIEvents.ElementContainer.TOPIC,
				UIEvents.ElementContainer.CHILDREN), childrenHandler);
		eventBroker.subscribe(
				UIEvents.buildTopic(UIEvents.UIElement.TOPIC, UIEvents.UIElement.TOBERENDERED),
				renderingChangeHandler);
		eventBroker.subscribe(
				UIEvents.buildTopic(UIEvents.UIElement.TOPIC, UIEvents.UIElement.VISIBLE),
				visibilityChangeHandler);
	}

	@PreDestroy
	void removeListeners() {
		eventBroker.unsubscribe(childrenHandler);
		eventBroker.unsubscribe(renderingChangeHandler);
		eventBroker.unsubscribe(visibilityChangeHandler);
	}
}
