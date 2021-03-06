// Copyright 2018 Sogou Inc. All rights reserved. 
// Use of this source code is governed by the Apache 2.0 
// license that can be found in the LICENSE file. 
package com.sogou.audiosource;

import android.media.AudioManager;
import android.util.Log;

import java.lang.reflect.Array;

/**
 * Created by zhouqilin on 16/9/22.
 */

public class DefaultAudioSource extends AbstractAudioSource implements Runnable {
    private static final int STATUS_NOT_READY = 0;
    private static final int STATUS_STARTED = 1;
    private static final int STATUS_PAUSED = 2;
    private static final int STATUS_DEAD = 3;


    private final IAudioDataProviderFactory mProviderFactory;

    private final long mMaxRecordTimeSec;

    private int mDataProviderStatus = STATUS_NOT_READY;
    private final Object mStatusLock = new Object();
    private final ConditionVar mResumeCondition;
    private final boolean mNewOutputBuffer;

    public DefaultAudioSource(IAudioDataProviderFactory providerFactory, boolean newOutputBuffer,
                              int maxRecordTimeSec){
        mProviderFactory = providerFactory;
        mMaxRecordTimeSec = maxRecordTimeSec;
        mResumeCondition = new ConditionVar(mStatusLock, new ConditionVar.ICondition() {
            @Override
            public boolean satisfied() {
                return mDataProviderStatus != STATUS_PAUSED;
            }
        });
        mNewOutputBuffer = newOutputBuffer;


    }

    public DefaultAudioSource(IAudioDataProviderFactory providerFactory){
        this(providerFactory, true, Integer.MAX_VALUE);
    }
    private Runnable scoConnectedCallback;
    private Runnable wireMicInputCallback;
    public void setSourceInputCallbacks(Runnable scoConnected,Runnable wireMicInput) {
        scoConnectedCallback = scoConnected;
        wireMicInputCallback = wireMicInput;
    }
    private IAudioDataProvider createRecorderNoLock(){
        IAudioDataProvider tmp = mProviderFactory.create();
        if(tmp.isInitialized()){
//            try {
//                tmp.start();
//            }catch (Exception e){
//                e.printStackTrace();
//                tmp.release();
//                tmp = null;}

        }else {
            tmp.release();
            tmp = null;
        }
        return tmp;
    }

    private IBufferFactory getOutputBufferFactory(int length){
        IBufferFactory bufferFactory;
        final boolean useShort = mProviderFactory.bytesPerFrame() == 2;
        if (useShort){
            bufferFactory = mNewOutputBuffer ? new NewBufferFactory.ShortBufferFactory() :
                    new CachedBufferFactory.ShortBufferFactory(length);
        }else {
            bufferFactory = mNewOutputBuffer ? new NewBufferFactory.ByteBufferFactory() :
                    new CachedBufferFactory.ByteBufferFactory(length);
        }
        return bufferFactory;
    }

    @Override
    public int start(){
        synchronized (mStatusLock){
            if (mDataProviderStatus != STATUS_STARTED){
                mDataProviderStatus = STATUS_STARTED;
                mStatusLock.notify();
            }
            return mDataProviderStatus == STATUS_STARTED ? 0 : -1;
        }
    }

    @Override
    public int pause(){
        synchronized (mStatusLock){
            if ( mDataProviderStatus == STATUS_STARTED){
                mDataProviderStatus = STATUS_PAUSED;
            }
            return mDataProviderStatus == STATUS_PAUSED ? 0 : -1;
        }
    }

    @Override
    public int stop(){
        synchronized (mStatusLock){
            if (mDataProviderStatus != STATUS_DEAD){
                //set status to dead, release call delayed or finalized
                mDataProviderStatus = STATUS_DEAD;
                mStatusLock.notify();
            }

            return mDataProviderStatus == STATUS_DEAD ? 0 : -1;
        }
    }

    private void releaseAudioDataSource(IAudioDataProvider audioDataProvider){
        if (audioDataProvider != null){
            audioDataProvider.release();
        }
    }

    private int waitForResume(){
        synchronized (mStatusLock){
            mResumeCondition.waitCondition();
            return mDataProviderStatus;
        }
    }

    private long getMaxRecordFrameCount(){
        return mMaxRecordTimeSec == Integer.MAX_VALUE ? Integer.MAX_VALUE : mMaxRecordTimeSec * mProviderFactory.samplingRateInHz();
    }

    private int pollStatusLocked(){
        synchronized (mStatusLock){
            return mDataProviderStatus;
        }
    }
    IAudioDataProvider dataProvider = null;
    public void reinitRecorder() {
        if(null!=dataProvider) {
            dataProvider.reinitRecorder();
        }
    }
    public void releaseRecorder() {
        if (dataProvider != null){
            dataProvider.release();
        }
    }

    @Override
    public void run(){
        boolean twoBytesPerFrame = mProviderFactory.bytesPerFrame() == 2;
        final int bufferSizeInBytes = mProviderFactory.bufferSizeInBytes();
        final Object audioBuffer = twoBytesPerFrame ? new short[bufferSizeInBytes / 2] :
                new byte[bufferSizeInBytes];
        final int bufferLen = Array.getLength(audioBuffer);
        final long maxRecordFrameCnt = getMaxRecordFrameCount();

        int errorCode = 0;
        Exception exception = null;

        long totalFrameCnt = 0;
        long packageNum = 0;

        final IBufferFactory outputBufferFactory = getOutputBufferFactory(bufferLen);


        try {
            dataProvider = createRecorderNoLock();

            if (dataProvider == null){
                errorCode = -1;
                //report finish
                fireOnEnd(errorCode, exception, 0);
                return;
            }

            dataProvider.setCallbacks(scoConnectedCallback,wireMicInputCallback);
            start();

            for (int i = 0;i < 40;i++){
                int status = pollStatusLocked();

                if (status == STATUS_DEAD){
                    dataProvider.stop();
                    fireOnEnd(errorCode, exception, totalFrameCnt);
                    return;
                }

                if (dataProvider.isReady()) {
                    break;
                }else {
                    Thread.sleep(100l);
                }
            }
            //report begin
            fireOnBegin();
            dataProvider.start();

            while (true){
                try {
                    int readFrameCnt = twoBytesPerFrame ? dataProvider.read((short[]) audioBuffer, 0, bufferLen) :
                            dataProvider.read((byte[]) audioBuffer, 0, bufferLen);
                    if (readFrameCnt < 0){
                        errorCode = readFrameCnt;
                        break;
                    }

                    final Object tmpWavData = outputBufferFactory.newBuffer(readFrameCnt);
                    System.arraycopy(audioBuffer, 0, tmpWavData, 0, readFrameCnt);
                    ++packageNum;

                    totalFrameCnt += readFrameCnt;
                    if (totalFrameCnt > maxRecordFrameCnt){
                        //report progress
                        fireOnNewData(tmpWavData, packageNum, totalFrameCnt - readFrameCnt, IAudioSourceListener.AUDIO_DATA_FLAG_SESSION_END);
                        break;
                    }

                    int status = pollStatusLocked();
                    final boolean pausedOrStopped = (status == STATUS_PAUSED || status == STATUS_DEAD);
                    if (pausedOrStopped){
                        dataProvider.stop();
                    }
                    //report progress
                    fireOnNewData(tmpWavData, packageNum, totalFrameCnt - readFrameCnt, pausedOrStopped ?
                            IAudioSourceListener.AUDIO_DATA_FLAG_SESSION_END : 0);

                    //!!! Continue to avoid missing pause signal !!!
                    if (!pausedOrStopped){
                        continue;
                    }
                    status = waitForResume();
                    if(status != STATUS_STARTED){
                        break;
                    }

                    dataProvider.start();

                }catch (Exception e){
                    errorCode = -1;
                    exception = e;
                    e.printStackTrace();
                    break;
                }
            }

            //report finish
            fireOnEnd(errorCode, exception, totalFrameCnt);
        } catch (Exception e1) {
            e1.printStackTrace();
        }
        finally {
            releaseAudioDataSource(dataProvider);
        }
    }

    @Override
    public int bytesPerSecond() {
        return mProviderFactory.samplingRateInHz() * mProviderFactory.bytesPerFrame();
    }

}