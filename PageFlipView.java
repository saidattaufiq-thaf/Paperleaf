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
package com.paperleaf.sketchbook.pageflip;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import com.eschao.android.widget.pageflip.OnPageFlipListener;
import com.eschao.android.widget.pageflip.PageFlip;
import com.eschao.android.widget.pageflip.PageFlipException;
import com.eschao.android.widget.pageflip.Page;

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
    private static final int ANIMATION_DURATION = 1000;
    
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
        // Default is 10, we set to 2 for extremely smooth curves like Heyzine
        mPageFlip.setPixelsOfMesh(2);
        
        // Set shadow colors for realistic effect
        // Edge shadow (top edge of folded page)
        mPageFlip.setShadowColorOfFoldEdges(
            0.05f,  // start color (darker)
            0.7f,   // start alpha (more visible)
            0.2f,   // end color
            0.0f    // end alpha (fade out)
        );
        
        // Base shadow (shadow under the folded page)
        mPageFlip.setShadowColorOfFoldBase(
            0.02f,  // start color (very dark)
            0.9f,   // start alpha (very visible)
            0.3f,   // end color
            0.0f    // end alpha (fade out)
        );
        
        // Set shadow width
        mPageFlip.setShadowWidthOfFoldEdges(2, 60, 0.4f);
        mPageFlip.setShadowWidthOfFoldBase(2, 80, 0.5f);
        
        // Set semi-perimeter ratio for curl radius
        // 0.75f provides a more natural curling radius for realistic flip
        mPageFlip.setSemiPerimeterRatio(0.75f);        
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
     * Set the textures for the current page and the next page.
     * 
     * @param currentFront The page currently visible.
     * @param currentBack The back side of the flipping sheet (revealed during flip).
     * @param nextFront The page that will be visible after the flip.
     */
    public void setPages(Bitmap currentFront, Bitmap currentBack, Bitmap nextFront) {
        if (mPageFlip != null && mSurfaceCreated) {
            Page firstPage = mPageFlip.getFirstPage();
            Page secondPage = mPageFlip.getSecondPage();
            
            if (secondPage != null) {
                // LANDSCAPE / DOUBLE PAGE MODE:
                // firstPage is the flipping page (e.g. Right Page)
                // currentFront: front of right page
                // currentBack: back of right page (revealed as you flip)
                // nextFront: the page underneath (on the left side or next spread)
                firstPage.setFirstTexture(currentFront);
                firstPage.setBackTexture(currentBack);
                firstPage.setSecondTexture(nextFront);
            } else {
                // PORTRAIT / SINGLE PAGE MODE:
                // In eschao's library for single page:
                // firstPage.setFirstTexture = Current Page
                // firstPage.setSecondTexture = Next Page (revealed underneath)
                // Back texture is usually a mirrored version of FirstTexture by default.
                firstPage.setFirstTexture(currentFront);
                firstPage.setSecondTexture(nextFront);
                // If you want a distinct back side in single page, you can try:
                firstPage.setBackTexture(currentBack);
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

    /**
     * Set page mode (single or double)
     */
    public void setPageMode(boolean isDoublePage) {
        if (mPageFlip != null) {
            mPageFlip.enableAutoPage(false); // disable auto to force manual mode
            // Note: eschao library handles page count based on surface size and auto-page
            // but we can influence it by calling onSurfaceChanged with specific logic if needed.
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mPageFlip == null) {
            return false;
        }
        
        float x = event.getX();        float y = event.getY();
        
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
                    
                    // Update textures after flip ends
                    if (mPageFlip.isEndedFlip()) {
                        Page firstPage = mPageFlip.getFirstPage();
                        // Promote the 'next' page to be the 'current' page
                        if (firstPage != null && firstPage.isSecondTextureSet()) {
                            firstPage.setFirstTextureWithSecond();
                        }
                    }
                    
                    requestRender();
                }
            }
        });
    }
    
    @Override    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
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
     * Clean up resources
     */    public void onDelete() {
        mSurfaceCreated = false;
    }
}