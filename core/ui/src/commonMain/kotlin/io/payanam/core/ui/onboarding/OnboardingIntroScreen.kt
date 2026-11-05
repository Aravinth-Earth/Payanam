//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.onboarding

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import kotlinx.coroutines.launch

/**
 * The three-slide onboarding tour, shared by every platform.
 *
 * This is Android's `AppOnboardingIntroScreen` moved rather than reimplemented: same pager, same spacing,
 * same indicator sizes, same copy, same actions. The differences are the two forced by leaving `app/`:
 *
 *  - the copy is read from [UiStrings] instead of `R.string`, because `R` is an Android
 *    artefact. The text is unchanged, and English and Tamil still sit together in one file.
 *  - [language] is a parameter. Deciding which language the user wants is OS interaction - per-app
 *    language on Android, `Locale.getDefault()` on the desktop - and this module holds none.
 *
 * There is no other platform-specific behaviour in this screen, which is why it is the right first
 * screen to prove the shared module on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingIntroScreen(
    language: UiLanguage,
    onFinished: () -> Unit,
) {
    val strings = UiStrings.of(language)
    val pages =
        listOf(
            OnboardingPage(
                title = strings.onboardingSlide1Title,
                desc = strings.onboardingSlide1Desc,
                icon = Icons.Default.Lock,
            ),
            OnboardingPage(
                title = strings.onboardingSlide2Title,
                desc = strings.onboardingSlide2Desc,
                icon = Icons.Default.AutoGraph,
            ),
            OnboardingPage(
                title = strings.onboardingSlide3Title,
                desc = strings.onboardingSlide3Desc,
                icon = Icons.Default.Style,
            ),
        )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) { pageIndex ->
                OnboardingSlideContent(page = pages[pageIndex])
            }

            // Pager Indicators & Actions
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pages.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier =
                                Modifier
                                    .size(if (isSelected) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
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
                val isLastPage = pagerState.currentPage == pages.size - 1
                Button(
                    onClick = {
                        if (isLastPage) {
                            onFinished()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = if (isLastPage) strings.onboardingActionStart else strings.onboardingActionNext,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!isLastPage) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                    }
                }

                // Skip Button
                AnimatedVisibility(
                    visible = !isLastPage,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    TextButton(onClick = onFinished) {
                        Text(
                            text = strings.onboardingActionSkip,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isLastPage) {
                    Spacer(modifier = Modifier.height(48.dp)) // Spacer to keep button height consistent
                }
            }
        }
    }
}

@Composable
private fun OnboardingSlideContent(page: OnboardingPage) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = page.desc,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = TextUnit.Unspecified,
        )
    }
}

private data class OnboardingPage(
    val title: String,
    val desc: String,
    val icon: ImageVector,
)
