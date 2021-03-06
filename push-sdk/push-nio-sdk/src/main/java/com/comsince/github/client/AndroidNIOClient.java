package com.comsince.github.client;

import com.comsince.github.core.*;
import com.comsince.github.core.callback.CompletedCallback;
import com.comsince.github.core.callback.ConnectCallback;
import com.comsince.github.core.callback.DataCallback;
import com.comsince.github.core.future.Cancellable;
import com.comsince.github.logger.Log;
import com.comsince.github.logger.LoggerFactory;
import com.comsince.github.push.Header;
import com.comsince.github.push.Signal;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;


/**
 *
 * 这里Android client，提供心跳回调机制，方便客户端自由定制
 * */
public class AndroidNIOClient implements ConnectCallback,DataCallback,CompletedCallback {
    Log log = LoggerFactory.getLogger(NIOClient.class);
    private AsyncServer asyncServer;
    private AsyncSocket asyncSocket;
    private String host;
    private int port;
    private Cancellable cancellable;

    volatile ConnectStatus connectStatus = ConnectStatus.DISCONNECT;

    Header receiveHeader = null;
    ByteBufferList receiveBuffer = new ByteBufferList();

    private PushMessageCallback pushMessageCallback;

    public void setPushMessageCallback(PushMessageCallback pushMessageCallback){
        this.pushMessageCallback = pushMessageCallback;
    }

    public AndroidNIOClient(String host, int port) {
        this.host = host;
        this.port = port;
        asyncServer = new AsyncServer(host+"-"+port);
    }

    public void connect(){
        asyncServer.post(new Runnable() {
            @Override
            public void run() {
                log.i("current connect status "+connectStatus);
                if(connectStatus == ConnectStatus.DISCONNECT){
                    connectStatus = ConnectStatus.CONNECTING;
                    cancellable = asyncServer.connectSocket(host,port,AndroidNIOClient.this);
                }
            }
        });
    }

    public void close(){
        if(cancellable != null){
            cancellable.cancel();
        }
        if(asyncSocket!= null){
            asyncSocket.close();
        }
    }

    public void sub(){
        sub("");
    }

    public void sub(String uid){
        //start register
        String tokenJson = "{\"uid\":\""+uid+"\"}";
        sendMessage(Signal.SUB, tokenJson, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("write completed",ex);
            }
        });

    }

    /**
     * @param interval 距离下次心跳间隔，单位毫秒
     * */
    public void heart(long interval){
        log.i("Android client send heartbeat");
        String heartInterval = "{\"interval\":"+interval+"}";
        sendMessage(Signal.PING, heartInterval, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("send heartbeat completed",ex);
            }
        });
    }

    public void sendMessage(Signal signal, String body, final CompletedCallback completedCallback){
        ByteBufferList bufferList = new ByteBufferList();
        Header header = new Header();
        header.setSignal(signal);
        header.setLength(body.getBytes().length);

        ByteBuffer allBuffer = ByteBufferList.obtain(Header.LENGTH + header.getLength());
        allBuffer.put(header.getContents());
        allBuffer.put(body.getBytes());
        allBuffer.flip();
        bufferList.add(allBuffer);

        Util.writeAll(asyncSocket, bufferList, new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                log.e("send heartbeat completed",ex);
                completedCallback.onCompleted(ex);
            }
        });
    }



    @Override
    public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
        if(receiveBuffer.remaining() == 0){
            ByteBufferList headerBuffer = bb.get(Header.LENGTH);
            receiveHeader = new Header(headerBuffer.getAll());
        }
        int bodyLength = receiveHeader.getLength();
        int read = bodyLength - receiveBuffer.remaining();
        int reallyRead = read > bb.remaining() ? bb.remaining() : read;
        bb.get(receiveBuffer,reallyRead);

        if(receiveBuffer.remaining() == bodyLength){
            String message = receiveBuffer.readString(Charset.forName("UTF-8"));
            String logMessage = "receive signal ["+receiveHeader.getSignal()+"] body-> "+message;
            log.i(logMessage);
            if(pushMessageCallback != null){
                pushMessageCallback.receiveMessage(receiveHeader.getSignal(),message);
            }
        }

    }

    @Override
    public void onConnectCompleted(Exception ex, AsyncSocket socket) {
        //ex为空，表示链接正常
        if(ex != null){
            if(pushMessageCallback != null){
                pushMessageCallback.receiveException(ex);
            }
            connectStatus = ConnectStatus.DISCONNECT;
            log.e("connect failed",ex);
            return;
        }


        connectStatus = ConnectStatus.CONNECTED;
        this.asyncSocket = socket;
        asyncSocket.setDataCallback(this);
        asyncSocket.setClosedCallback(this);

        if(pushMessageCallback != null){
            pushMessageCallback.onConnected();
        }
        //sub();
    }

    @Override
    public void onCompleted(Exception ex) {
        if(ex != null) {
            log.e("onCompleted ",ex);
        }
        connectStatus = ConnectStatus.DISCONNECT;

        if(pushMessageCallback != null){
            pushMessageCallback.receiveException(ex);
        }
    }


}