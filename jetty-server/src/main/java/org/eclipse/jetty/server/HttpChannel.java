//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;

import org.eclipse.jetty.continuation.ContinuationThrowable;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** HttpChannel.
 * Represents a single endpoint for HTTP semantic processing.
 * The HttpChannel is both a HttpParser.RequestHandler, where it passively receives events from 
 * an incoming HTTP request, and a Runnable, where it actively takes control of the request/response
 * life cycle and calls the application (perhaps suspending and resuming with multiple calls to run).
 * The HttpChannel signals the switch from passive mode to active mode by returning true to one of the 
 * HttpParser.RequestHandler callbacks.   The completion of the active phase is signalled by a call to 
 * HttpTransport.httpChannelCompleted().
 * 
 */
public class HttpChannel implements HttpParser.RequestHandler, Runnable
{
    private static final Logger LOG = Log.getLogger(HttpChannel.class);
    private static final ThreadLocal<HttpChannel> __currentChannel = new ThreadLocal<>();

    public static HttpChannel getCurrentHttpChannel()
    {
        return __currentChannel.get();
    }

    protected static void setCurrentHttpChannel(HttpChannel channel)
    {
        __currentChannel.set(channel);
    }

    private final AtomicBoolean _committed = new AtomicBoolean();
    private final AtomicInteger _requests = new AtomicInteger();
    private final Connector _connector;
    private final HttpConfiguration _configuration;
    private final EndPoint _endPoint;
    private final HttpTransport _transport;
    private final HttpURI _uri;
    private final HttpChannelState _state;
    private final Request _request;
    private final Response _response;
    private HttpVersion _version = HttpVersion.HTTP_1_1;
    private boolean _expect = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;

    public HttpChannel(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput input)
    {
        _connector = connector;
        _configuration = configuration;
        _endPoint = endPoint;
        _transport = transport;

        _uri = new HttpURI(URIUtil.__CHARSET);
        _state = new HttpChannelState(this);
        _request = new Request(this, input);
        _response = new Response(this, new HttpOutput(this));
    }

    public HttpChannelState getState()
    {
        return _state;
    }

    /**
     * @return the number of requests handled by this connection
     */
    public int getRequests()
    {
        return _requests.get();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _configuration;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Request getRequest()
    {
        return _request;
    }

    public Response getResponse()
    {
        return _response;
    }

    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    public InetSocketAddress getLocalAddress()
    {
        return _endPoint.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress()
    {
        return _endPoint.getRemoteAddress();
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (_response.isCommitted())
                    throw new IOException("Committed before 100 Continues");

                // TODO: break this dependency with HttpGenerator
                boolean committed = commitResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue"); // TODO: better message
            }
        }
    }

    public void reset()
    {
        _committed.set(false);
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _request.recycle();
        _response.recycle();
        _uri.clear();
    }

    @Override
    public void run()
    {
        LOG.debug("{} handle enter", this);

        setCurrentHttpChannel(this);

        String threadName = null;
        if (LOG.isDebugEnabled())
        {
            threadName = Thread.currentThread().getName();
            Thread.currentThread().setName(threadName + " - " + _uri);
        }

        try
        {
            // Loop here to handle async request redispatches.
            // The loop is controlled by the call to async.unhandle in the
            // finally block below.  Unhandle will return false only if an async dispatch has
            // already happened when unhandle is called.
            boolean handling = _state.handling();

            while (handling && getServer().isRunning())
            {
                try
                {
                    _request.setHandled(false); // TODO: is this right here ?
                    _response.getHttpOutput().reopen();

                    if (_state.isInitial())
                    {
                        _request.setDispatcherType(DispatcherType.REQUEST);
                        getHttpConfiguration().customize(_request);
                        getServer().handle(this);
                    }
                    else
                    {
                        _request.setDispatcherType(DispatcherType.ASYNC);
                        getServer().handleAsync(this);
                    }
                }
                catch (ContinuationThrowable e)
                {
                    LOG.ignore(e);
                }
                catch (EofException e)
                {
                    LOG.debug(e);
                    _state.error(e);
                    _request.setHandled(true);
                }
                catch (Throwable e) // TODO: consider catching only Exception
                {
                    LOG.warn(String.valueOf(_uri), e);
                    _state.error(e);
                    _request.setHandled(true);
                    handleException(e);
                }
                finally
                {
                    handling = !_state.unhandle();
                }
            }
        }
        finally
        {
            if (threadName != null && LOG.isDebugEnabled())
                Thread.currentThread().setName(threadName);
            setCurrentHttpChannel(null);
            
            if (_state.isCompleting())
            {
                try
                {
                    _state.completed();
                    if (_expect100Continue)
                    {
                        LOG.debug("100 continues not sent");
                        // We didn't send 100 continues, but the latest interpretation
                        // of the spec (see httpbis) is that the client will either
                        // send the body anyway, or close.  So we no longer need to
                        // do anything special here other than make the connection not persistent
                        _expect100Continue = false;
                        if (!_response.isCommitted())
                            _response.getHttpFields().add(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE.toString());
                        else
                            LOG.warn("Can't close non-100 response");
                    }

                    if (!_response.isCommitted() && !_request.isHandled())
                        _response.sendError(404);

                    // Complete generating the response
                    _response.complete();

                }
                catch(EofException e)
                {
                    LOG.debug(e);
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
                finally
                {
                    _request.setHandled(true);
                    _transport.httpChannelCompleted();
                }
            }

            LOG.debug("{} handle exit", this);
        }
    }

    /**
     * <p>Sends an error 500, performing a special logic to detect whether the request is suspended,
     * to avoid concurrent writes from the application.</p>
     * <p>It may happen that the application suspends, and then throws an exception, while an application
     * spawned thread writes the response content; in such case, we attempt to commit the error directly
     * bypassing the {@link ErrorHandler} mechanisms and the response OutputStream.</p>
     *
     * @param x the Throwable that caused the problem
     */
    protected void handleException(Throwable x)
    {
        try
        {
            if (_state.isSuspended())
            {
                HttpFields fields = new HttpFields();
                ResponseInfo info = new ResponseInfo(_request.getHttpVersion(), fields, 0, Response.SC_INTERNAL_SERVER_ERROR, null, _request.isHead());
                boolean committed = commitResponse(info, null, true);
                if (!committed)
                    LOG.warn("Could not send response error 500, response is already committed");
            }
            else
            {
                _request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,x);
                _request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE,x.getClass());
                _response.sendError(500, x.getMessage());
            } 
        }
        catch (IOException e)
        {
            // We tried our best, just log
            LOG.debug("Could not commit response error 500", e);
        }
    }

    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{r=%s,a=%s}",
                getClass().getSimpleName(),
                hashCode(),
                _requests,
                _state.getState());
    }

    @Override
    public boolean startRequest(HttpMethod httpMethod, String method, String uri, HttpVersion version)
    {
        _expect = false;
        _expect100Continue = false;
        _expect102Processing = false;

        if (_request.getTimeStamp() == 0)
            _request.setTimeStamp(System.currentTimeMillis());
        _request.setMethod(httpMethod, method);

        if (httpMethod == HttpMethod.CONNECT)
            _uri.parseConnect(uri);
        else
            _uri.parse(uri);
        _request.setUri(_uri);

        String path;
        try
        {
            path = _uri.getDecodedPath();
        }
        catch (Exception e)
        {
            LOG.warn("Failed UTF-8 decode for request path, trying ISO-8859-1");
            LOG.ignore(e);
            path = _uri.getDecodedPath(StringUtil.__ISO_8859_1);
        }
        String info = URIUtil.canonicalPath(path);

        if (info == null)
            info = "/";
        _request.setPathInfo(info);
        _version = version == null ? HttpVersion.HTTP_0_9 : version;
        _request.setHttpVersion(_version);

        return false;
    }

    @Override
    public boolean parsedHeader(HttpHeader header, String name, String value)
    {
        if (value == null)
            value = "";
        if (header != null)
        {
            switch (header)
            {
                case EXPECT:
                    HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                    switch (expect == null ? HttpHeaderValue.UNKNOWN : expect)
                    {
                        case CONTINUE:
                            _expect100Continue = true;
                            break;

                        case PROCESSING:
                            _expect102Processing = true;
                            break;

                        default:
                            String[] values = value.split(",");
                            for (int i = 0; values != null && i < values.length; i++)
                            {
                                expect = HttpHeaderValue.CACHE.get(values[i].trim());
                                if (expect == null)
                                    _expect = true;
                                else
                                {
                                    switch (expect)
                                    {
                                        case CONTINUE:
                                            _expect100Continue = true;
                                            break;
                                        case PROCESSING:
                                            _expect102Processing = true;
                                            break;
                                        default:
                                            _expect = true;
                                    }
                                }
                            }
                    }
                    break;

                case CONTENT_TYPE:
                    MimeTypes.Type mime = MimeTypes.CACHE.get(value);
                    String charset = (mime == null || mime.getCharset() == null) ? MimeTypes.getCharsetFromContentType(value) : mime.getCharset().toString();
                    if (charset != null)
                        _request.setCharacterEncodingUnchecked(charset);
                    break;
            }
        }
        if (name != null)
            _request.getHttpFields().add(name, value);
        return false;
    }
    
    @Override
    public boolean parsedHostHeader(String host, int port)
    {
        _request.setServerName(host);
        _request.setServerPort(port);
        return false;
    }

    @Override
    public boolean headerComplete()
    {
        _requests.incrementAndGet();
        boolean persistent;
        switch (_version)
        {
            case HTTP_0_9:
                persistent = false;
                break;
            case HTTP_1_0:
                persistent = _request.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                if (persistent)
                    _response.getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);

                if (getServer().getSendDateHeader())
                    _response.getHttpFields().putDateField(HttpHeader.DATE.toString(), _request.getTimeStamp());
                break;

            case HTTP_1_1:
                persistent = !_request.getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());

                if (!persistent)
                    _response.getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

                if (getServer().getSendDateHeader())
                    _response.getHttpFields().putDateField(HttpHeader.DATE.toString(), _request.getTimeStamp());

                if (_expect)
                {
                    badMessage(HttpStatus.EXPECTATION_FAILED_417,null);
                    return true;
                }

                break;
            default:
                throw new IllegalStateException();
        }

        _request.setPersistent(persistent);

        // Either handle now or wait for first content/message complete
        return _expect100Continue;
    }

    @Override
    public boolean content(ByteBuffer ref)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} content {}", this, BufferUtil.toDetailString(ref));
        _request.getHttpInput().content(ref);
        return true;
    }

    @Override
    public boolean messageComplete(long contentLength)
    {
        _request.getHttpInput().shutdown();
        return true;
    }

    @Override
    public boolean earlyEOF()
    {
        _request.getHttpInput().shutdown();
        return false;
    }

    @Override
    public void badMessage(int status, String reason)
    {
        if (status < 400 || status > 599)
            status = HttpStatus.BAD_REQUEST_400;
        
        try
        {
            if (_state.handling())
            {
                commitResponse(new ResponseInfo(HttpVersion.HTTP_1_1,new HttpFields(),0,status,reason,false),null,true);
                _state.unhandle();
            }
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }
        finally
        {
            _state.completed();
        }
    }

    protected boolean commitResponse(ResponseInfo info, ByteBuffer content, boolean complete) throws IOException
    {
        boolean committed = _committed.compareAndSet(false, true);
        if (committed)
            _transport.commit(info, content, complete);
        return committed;
    }

    protected boolean isCommitted()
    {
        return _committed.get();
    }

    /**
     * <p>Requests to write (in a blocking way) the given response content buffer,
     * committing the response if needed.</p>
     *
     * @param content  the content buffer to write
     * @param complete whether the content is complete for the response
     * @throws IOException if the write fails
     */
    protected void write(ByteBuffer content, boolean complete) throws IOException
    {
        if (isCommitted())
        {
            _transport.write(content, complete);
        }
        else
        {
            ResponseInfo info = _response.newResponseInfo();
            boolean committed = commitResponse(info, content, complete);
            if (!committed)
                throw new IOException("Concurrent commit"); // TODO: better message
        }
    }

    protected void execute(Runnable task)
    {
        _connector.getExecutor().execute(task);
    }

    // TODO: remove
    public ScheduledExecutorService getScheduler()
    {
        return _connector.getScheduler();
    }
}
