/*
 * Copyright (C) 2016 eschao <esc.chao@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.eschao.android.widget.pageflip;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL Surface View for Page Flip with Heyzine-style configuration
 *
 * @author eschao
 */
public class PageFlipView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "PageFlipView";
    
    private PageFlip mPageFlip;
    private OnPageFlipListener mExternalListener;
    private boolean mSurfaceCreated = false;
    
    // Animation duration in milliseconds
    private static final int ANIMATION_DURATION = 300;
    
    public PageFlipView(Context context) {
        super(context);
        init();
    }
    
    public PageFlipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();    }
    
    private void init() {
        // Set OpenGL ES 2.0
        setEGLContextClientVersion(2);
        
        // Create PageFlip engine
        mPageFlip = new PageFlip(getContext());
        
        // Set this as renderer
        setRenderer(this);
        
        // Render only when dirty (for better performance)
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        
        // Configure PageFlip for Heyzine-like effect
        configurePageFlip();
    }
    
    /**
     * Configure PageFlip for realistic Heyzine-style animation
     */
    private void configurePageFlip() {
        // Set mesh density for smooth curves (lower value = smoother)
        // Default is 10, we set to 5 for very smooth curves like Heyzine
        mPageFlip.setPixelsOfMesh(5);
        
        // Set shadow colors for realistic effect
        // Edge shadow (top edge of folded page)
        mPageFlip.setShadowColorOfFoldEdges(
            0.1f,   // start color (darker)
            0.6f,   // start alpha (more visible)
            0.3f,   // end color
            0.0f    // end alpha (fade out)
        );
        
        // Base shadow (shadow under the folded page)
        mPageFlip.setShadowColorOfFoldBase(
            0.05f,  // start color (very dark)
            0.8f,   // start alpha (very visible)
            0.4f,   // end color
            0.0f    // end alpha (fade out)
        );
        
        // Set shadow width
        mPageFlip.setShadowWidthOfFoldEdges(5, 40, 0.3f);
        mPageFlip.setShadowWidthOfFoldBase(3, 50, 0.4f);
        
        // Set semi-perimeter ratio for curl radius
        mPageFlip.setSemiPerimeterRatio(0.85f);        
        // Enable click to flip
        mPageFlip.enableClickToFlip(true);
        mPageFlip.setWidthRatioOfClickToFlip(0.5f);
        
        // Set internal listener
        mPageFlip.setListener(new OnPageFlipListener() {
            @Override
            public boolean canFlipForward() {
                return mExternalListener == null || 
                       mExternalListener.canFlipForward();
            }
            
            @Override
            public boolean canFlipBackward() {
                return mExternalListener == null || 
                       mExternalListener.canFlipBackward();
            }
        });
    }
    
    /**
     * Set the first page (and optionally second page for double-page mode)
     * 
     * @param frontPage Bitmap of the front page
     * @param backPage Bitmap of the back page (can be null for single page mode)
     */
    public void setFirstPage(Bitmap frontPage, Bitmap backPage) {
        if (mPageFlip != null && mSurfaceCreated) {
            Page firstPage = mPageFlip.getFirstPage();
            Page secondPage = mPageFlip.getSecondPage();
            
            if (firstPage != null) {
                firstPage.setFirstTexture(frontPage);
            }
            
            if (secondPage != null && backPage != null) {
                secondPage.setFirstTexture(backPage);
            }
            
            requestRender();
        }
    }
    
    /**
     * Set the next page for flipping
     * 
     * @param page Bitmap of the next page
     */
    public void setNextPage(Bitmap page) {        if (mPageFlip != null && mSurfaceCreated) {
            Page secondPage = mPageFlip.getSecondPage();
            
            if (secondPage != null) {
                // In double page mode, set as second page
                secondPage.setSecondTexture(page);
            } else {
                // Single page mode - update first page
                Page firstPage = mPageFlip.getFirstPage();
                if (firstPage != null) {
                    firstPage.setSecondTexture(page);
                }
            }
            
            requestRender();
        }
    }
    
    /**
     * Set external page flip listener
     * 
     * @param listener Listener for page flip events
     */
    public void setOnPageFlipListener(OnPageFlipListener listener) {
        mExternalListener = listener;
    }
    
    /**
     * Enable/disable auto page mode (landscape = double page, portrait = single page)
     * 
     * @param isAuto true to enable auto page mode
     * @return true if pages were recreated
     */
    public boolean enableAutoPage(boolean isAuto) {
        if (mPageFlip != null) {
            boolean result = mPageFlip.enableAutoPage(isAuto);
            if (result) {
                requestRender();
            }
            return result;
        }
        return false;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mPageFlip == null) {
            return false;
        }
                float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mPageFlip.onFingerDown(x, y);
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (mPageFlip.onFingerMove(x, y)) {
                    requestRender();
                }
                return true;
                
            case MotionEvent.ACTION_UP:
                if (mPageFlip.onFingerUp(x, y, ANIMATION_DURATION)) {
                    // Animation started
                    startAnimation();
                } else {
                    // No animation, just render final state
                    requestRender();
                }
                return true;
        }
        
        return super.onTouchEvent(event);
    }
    
    /**
     * Start flip animation
     */
    private void startAnimation() {
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        
        // Animation loop
        post(new Runnable() {
            @Override
            public void run() {
                if (mPageFlip != null && mPageFlip.animating()) {
                    requestRender();
                    post(this);
                } else {
                    // Animation finished
                    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                    requestRender();
                }
            }
        });
    }
        @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            if (mPageFlip != null) {
                mPageFlip.onSurfaceCreated();
                mSurfaceCreated = true;
            }
        } catch (PageFlipException e) {
            Log.e(TAG, "Failed to create surface", e);
        }
    }
    
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (mPageFlip != null && mSurfaceCreated) {
            try {
                mPageFlip.onSurfaceChanged(width, height);
            } catch (PageFlipException e) {
                Log.e(TAG, "Failed to change surface", e);
            }
        }
    }
    
    @Override
    public void onDrawFrame(GL10 gl) {
        if (mPageFlip != null && mSurfaceCreated) {
            if (mPageFlip.isStartedFlip()) {
                mPageFlip.drawFlipFrame();
            } else {
                mPageFlip.drawPageFrame();
            }
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        if (mPageFlip != null) {
            // Delete unused textures to free memory
            mPageFlip.deleteUnusedTextures();
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
    }
    
    /**
     * Clean up resources     */
    public void onDelete() {
        mSurfaceCreated = false;
    }
}