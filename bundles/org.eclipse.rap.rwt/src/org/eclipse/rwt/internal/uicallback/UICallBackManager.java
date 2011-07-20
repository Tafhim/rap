/*******************************************************************************
 * Copyright (c) 2007, 2011 Innoopract Informationssysteme GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Innoopract Informationssysteme GmbH - initial API and implementation
 *     EclipseSource - ongoing development
 ******************************************************************************/
package org.eclipse.rwt.internal.uicallback;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.rwt.SessionSingletonBase;
import org.eclipse.rwt.internal.lifecycle.JavaScriptResponseWriter;
import org.eclipse.rwt.internal.service.ContextProvider;
import org.eclipse.rwt.internal.service.IServiceStateInfo;
import org.eclipse.rwt.internal.util.SerializableLock;
import org.eclipse.rwt.service.*;
import org.eclipse.swt.internal.SerializableCompatibility;


public final class UICallBackManager implements SerializableCompatibility {

  private static final int DEFAULT_REQUEST_CHECK_INTERVAL = 30000;

  private static final String FORCE_UI_CALLBACK
    = UICallBackManager.class.getName() + "#forceUICallBack";

  public static UICallBackManager getInstance() {
    return ( UICallBackManager )SessionSingletonBase.getInstance( UICallBackManager.class );
  }

  private final IdManager idManager;

  private final SerializableLock lock;
  // Flag that indicates whether a request is processed. In that case no
  // notifications are sent to the client.
  private boolean uiThreadRunning;
  // indicates whether the display has runnables to execute
  private boolean hasRunnables;
  private boolean wakeCalled;
  private int requestCheckInterval;
  private transient CallBackRequestTracker callBackRequestTracker;

  private UICallBackManager() {
    lock = new SerializableLock();
    idManager = new IdManager();
    uiThreadRunning = false;
    wakeCalled = false;
    requestCheckInterval = DEFAULT_REQUEST_CHECK_INTERVAL;
    callBackRequestTracker = new CallBackRequestTracker();
  }

  public boolean isCallBackRequestBlocked() {
    synchronized( lock ) {
      return !callBackRequestTracker.hasActive();
    }
  }

  public void wakeClient() {
    synchronized( lock ) {
      if( !uiThreadRunning ) {
        releaseBlockedRequest();
      }
    }
  }

  public void releaseBlockedRequest() {
    synchronized( lock ) {
      wakeCalled = true;
      lock.notifyAll();
    }
  }

  public void setHasRunnables( boolean hasRunnables ) {
    synchronized( lock ) {
      this.hasRunnables = hasRunnables;
    }
    if( hasRunnables && isUICallBackActive() ) {
      ContextProvider.getStateInfo().setAttribute( FORCE_UI_CALLBACK, Boolean.TRUE );
    }
  }

  public void setRequestCheckInterval( int requestCheckInterval ) {
    this.requestCheckInterval = requestCheckInterval;
  }

  public void notifyUIThreadStart() {
    synchronized( lock ) {
      uiThreadRunning = true;
    }
  }

  public void notifyUIThreadEnd() {
    synchronized( lock ) {
      uiThreadRunning = false;
      if( hasRunnables ) {
        wakeClient();
      }
    }
  }

  public void activateUICallBacksFor( final String id ) {
    idManager.add( id );
  }

  public void deactivateUICallBacksFor( final String id ) {
    int size = idManager.remove( id );
    if( size == 0 ) {
      releaseBlockedRequest();
    }
  }

  boolean hasRunnables() {
    synchronized( lock ) {
      return hasRunnables;
    }
  }

  boolean processRequest( HttpServletResponse response ) {
    boolean result = true;
    synchronized( lock ) {
      if( isCallBackRequestBlocked() ) {
        releaseBlockedRequest();
      }
      if( mustBlockCallBackRequest() ) {
        Thread currentThread = Thread.currentThread();
        callBackRequestTracker.activate( currentThread );
        SessionTerminationListener listener = attachSessionTerminationListener();
        try {
          boolean canRelease = false;
          wakeCalled = false;
          while( !wakeCalled && !canRelease ) {
            lock.wait( requestCheckInterval );
            canRelease = canReleaseBlockedRequest( response );
          }
          result = callBackRequestTracker.isActive( currentThread );
        } catch( InterruptedException ie ) {
          result = false;
          Thread.interrupted(); // Reset interrupted state, see bug 300254
        } finally {
          listener.detach();
          callBackRequestTracker.deactivate( currentThread );
        }
      }
    }
    return result;
  }

  private boolean canReleaseBlockedRequest( HttpServletResponse response ) {
    boolean result = false;
    if( !mustBlockCallBackRequest() ) {
      result = true;
    } else if( !isConnectionAlive( response ) ) {
      result = true;
    } else if( !callBackRequestTracker.isActive( Thread.currentThread() ) ) {
      result = true;
    }
    return result;
  }

  boolean mustBlockCallBackRequest() {
    return isUICallBackActive() && !hasRunnables;
  }

  boolean isUICallBackActive() {
    return !idManager.isEmpty();
  }

  boolean needsActivation() {
    return isUICallBackActive() || forceUICallBackForPendingRunnables();
  }

  private Object readResolve() {
    callBackRequestTracker = new CallBackRequestTracker();
    return this;
  }

  private static SessionTerminationListener attachSessionTerminationListener() {
    ISessionStore sessionStore = ContextProvider.getSession();
    SessionTerminationListener result = new SessionTerminationListener( sessionStore );
    result.attach();
    return result;
  }

  private static boolean isConnectionAlive( HttpServletResponse response ) {
    boolean result;
    try {
      JavaScriptResponseWriter responseWriter = new JavaScriptResponseWriter( response );
      responseWriter.write( " " );
      result = !responseWriter.checkError();
    } catch( IOException ioe ) {
      result = false;
    }
    return result;
  }

  private static boolean forceUICallBackForPendingRunnables() {
    boolean result = false;
    IServiceStateInfo stateInfo = ContextProvider.getStateInfo();
    if( stateInfo != null ) {
      result = Boolean.TRUE.equals( stateInfo.getAttribute( FORCE_UI_CALLBACK ) );
    }
    return result;
  }

  private static class SessionTerminationListener
    implements SessionStoreListener, SerializableCompatibility
  {
    private transient final Thread currentThread;
    private transient final ISessionStore sessionStore;

    private SessionTerminationListener( ISessionStore sessionStore ) {
      this.sessionStore = sessionStore;
      this.currentThread = Thread.currentThread();
    }

    public void attach() {
      sessionStore.addSessionStoreListener( this );
    }

    public void detach() {
      sessionStore.removeSessionStoreListener( this );
    }

    public void beforeDestroy( SessionStoreEvent event ) {
      currentThread.interrupt();
    }
  }

  private static class CallBackRequestTracker {

    private transient List<Thread> callBackRequests;

    CallBackRequestTracker() {
      callBackRequests = new LinkedList<Thread>();
    }

    void deactivate( Thread thread ) {
      callBackRequests.remove( thread );
    }

    void activate( Thread thread ) {
      callBackRequests.add( 0, thread );
    }

    boolean hasActive() {
      return callBackRequests.isEmpty();
    }

    boolean isActive( Thread thread ) {
      return !hasActive() && callBackRequests.get( 0 ) == thread;
    }
  }
}
