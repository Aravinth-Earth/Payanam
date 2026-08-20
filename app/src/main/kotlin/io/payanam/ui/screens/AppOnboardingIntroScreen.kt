//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.payanam.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
/**
 * App onboarding intro screen.
 */
fun AppOnboardingIntroScreen(
    onFinished: () -> Unit,
) {
    /** Pages. */
    val pages = listOf(
        /** Onboarding page. */
        OnboardingPage(
            titleRes = R.string.onboarding_slide1_title,
            descRes = R.string.onboarding_slide1_desc,
            icon = Icons.Default.Lock,
        ),
        /** Onboarding page. */
        OnboardingPage(
            titleRes = R.string.onboarding_slide2_title,
            descRes = R.string.onboarding_slide2_desc,
            icon = Icons.Default.AutoGraph,
        ),
        /** Onboarding page. */
        OnboardingPage(
            titleRes = R.string.onboarding_slide3_title,
            descRes = R.string.onboarding_slide3_desc,
            icon = Icons.Default.Style,
        ),
    )

    /** Pager state. */
    val pagerState = rememberPagerState(pageCount = { pages.size })
    /** Scope. */
    val scope = rememberCoroutineScope()

    /** Surface. */
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        /** Column. */
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /** Horizontal pager. */
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { pageIndex ->
                /** Onboarding slide content. */
                OnboardingSlideContent(pages[pageIndex])
            }

            // Pager Indicators & Actions
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Indicators
                /** Row. */
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Repeat. */
                    repeat(pages.size) { index ->
                        /** Is selected. */
                        val isSelected = pagerState.currentPage == index
                        /** Box. */
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    /** If. */
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    },
                                ),
                        )
                    }
                }

                // Action Button
                /** Is last page. */
                val isLastPage = pagerState.currentPage == pages.size - 1
                /** Button. */
                Button(
                    onClick = {
                        /** If. */
                        if (isLastPage) {
                            /** On finished. */
                            onFinished()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    /** Text. */
                    Text(
                        text = if (isLastPage) {
                            /** String resource. */
                            stringResource(id = R.string.onboarding_action_start)
                        } else {
                            /** String resource. */
                            stringResource(id = R.string.loc_next)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    /** If. */
                    if (!isLastPage) {
                        /** Spacer. */
                        Spacer(modifier = Modifier.width(8.dp))
                        /** Icon. */
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                    }
                }

                // Skip Button
                /** Animated visibility. */
                AnimatedVisibility(
                    visible = !isLastPage,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    /** Text button. */
                    TextButton(onClick = onFinished) {
                        /** Text. */
                        Text(
                            text = stringResource(id = R.string.onboarding_action_skip),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                /** If. */
                if (isLastPage) {
                    /** Spacer. */
                    Spacer(modifier = Modifier.height(48.dp)) // Spacer to keep button height consistent
                }
            }
        }
    }
}

@Composable
private fun OnboardingSlideContent(page: OnboardingPage) {
    /** Column. */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        /** Surface. */
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            /** Box. */
            Box(contentAlignment = Alignment.Center) {
                /** Icon. */
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        /** Spacer. */
        Spacer(modifier = Modifier.height(48.dp))

        /** Text. */
        Text(
            text = stringResource(id = page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        /** Spacer. */
        Spacer(modifier = Modifier.height(16.dp))

        /** Text. */
        Text(
            text = stringResource(id = page.descRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
        )
    }
}

private data class OnboardingPage(
    /** Title res. */
    val titleRes: Int,
    /** Desc res. */
    val descRes: Int,
    /** Icon. */
    val icon: ImageVector,
)
