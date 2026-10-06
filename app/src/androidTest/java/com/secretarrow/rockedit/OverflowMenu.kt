package com.secretarrow.rockedit

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

/**
 * Shared helper for tapping overflow menu items regardless of where they sit
 * in the menu (v0.20.0 menu reorganization pushed most items below the fold).
 *
 * The overflow popup is an AdapterView (MenuDropDownListView) that only
 * materializes visible rows: items near the top have live TextViews, deeper
 * items do not exist as views until the list scrolls. Espresso's own error
 * message prescribes the fix — load the row through [Espresso.onData].
 *
 * Strategy:
 *  1. Open the overflow popup.
 *  2. Try a direct view match (works for materialized, on-screen rows).
 *  3. Fall back to onData against the popup root: the AdapterView scrolls to
 *     the row, materializes it, and clicks it. MenuItemImpl.toString() returns
 *     the item title, so the data matcher compares toString() to the title.
 */
object OverflowMenu {
    fun tap(titleRes: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = context.getString(titleRes)
        openActionBarOverflowOrOptionsMenu(context)
        try {
            onView(allOfDisplayed(withText(title))).perform(click())
        } catch (_: Throwable) {
            Espresso
                .onData(menuItemWithTitle(title))
                .inRoot(isPlatformPopup())
                .perform(click())
        }
    }

    private fun allOfDisplayed(matcher: Matcher<View>): Matcher<View> = org.hamcrest.Matchers.allOf(matcher, isDisplayed())

    /** Matches an overflow adapter row whose toString() is the item title. */
    private fun menuItemWithTitle(title: String): Matcher<Any> =
        object : TypeSafeMatcher<Any>() {
            override fun matchesSafely(item: Any): Boolean = item.toString() == title

            override fun describeTo(description: Description) {
                description.appendText("overflow menu item with title: ").appendText(title)
            }
        }
}
