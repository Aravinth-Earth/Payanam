//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.payanam.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension

/**
 * DimensionIconOption.
 */
data class DimensionIconOption(
    /** Key. */
    val key: String,
    /** Image vector. */
    val imageVector: ImageVector,
)

/**
 * DimensionIconCatalog.
 */
object DimensionIconCatalog {
    private fun loggerOrNull(): UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    /** Options. */
    val options: List<DimensionIconOption> = listOf(
        /** Dimension icon option. */
        DimensionIconOption("work", Icons.Outlined.WorkOutline),
        /** Dimension icon option. */
        DimensionIconOption("favorite", Icons.Outlined.FavoriteBorder),
        /** Dimension icon option. */
        DimensionIconOption("groups", Icons.Outlined.Groups),
        /** Dimension icon option. */
        DimensionIconOption("trending_up", Icons.Outlined.TrendingUp),
        /** Dimension icon option. */
        DimensionIconOption("account_balance_wallet", Icons.Outlined.AccountBalanceWallet),
        /** Dimension icon option. */
        DimensionIconOption("self_improvement", Icons.Outlined.SelfImprovement),
        /** Dimension icon option. */
        DimensionIconOption("sports_esports", Icons.Outlined.SportsEsports),
        /** Dimension icon option. */
        DimensionIconOption("menu_book", Icons.Outlined.MenuBook),
        /** Dimension icon option. */
        DimensionIconOption("volunteer_activism", Icons.Outlined.VolunteerActivism),
        /** Dimension icon option. */
        DimensionIconOption("category", Icons.Outlined.Category),
        /** Dimension icon option. */
        DimensionIconOption("fitness_center", Icons.Outlined.FitnessCenter),
        /** Dimension icon option. */
        DimensionIconOption("hiking", Icons.Outlined.Hiking),
        /** Dimension icon option. */
        DimensionIconOption("home", Icons.Outlined.Home),
        /** Dimension icon option. */
        DimensionIconOption("psychology", Icons.Outlined.Psychology),
        /** Dimension icon option. */
        DimensionIconOption("music_note", Icons.Outlined.MusicNote),
        /** Dimension icon option. */
        DimensionIconOption("event", Icons.Outlined.Event),
        /** Dimension icon option. */
        DimensionIconOption("schedule", Icons.Outlined.Schedule),
        /** Dimension icon option. */
        DimensionIconOption("computer", Icons.Outlined.Computer),
        /** Dimension icon option. */
        DimensionIconOption("restaurant", Icons.Outlined.Restaurant),
        /** Dimension icon option. */
        DimensionIconOption("pets", Icons.Outlined.Pets),
        /** Dimension icon option. */
        DimensionIconOption("child_care", Icons.Outlined.ChildCare),
        /** Dimension icon option. */
        DimensionIconOption("eco", Icons.Outlined.Eco),
        /** Dimension icon option. */
        DimensionIconOption("travel_explore", Icons.Outlined.TravelExplore),
        /** Dimension icon option. */
        DimensionIconOption("public", Icons.Outlined.Public),
        /** Dimension icon option. */
        DimensionIconOption("shield", Icons.Outlined.Shield),
        /** Dimension icon option. */
        DimensionIconOption("local_cafe", Icons.Outlined.LocalCafe),
        /** Dimension icon option. */
        DimensionIconOption("nightlight", Icons.Outlined.Nightlight),
        /** Dimension icon option. */
        DimensionIconOption("directions_run", Icons.Outlined.DirectionsRun),
        /** Dimension icon option. */
        DimensionIconOption("star_outline", Icons.Outlined.StarOutline),
        /** Dimension icon option. */
        DimensionIconOption("bolt", Icons.Outlined.Bolt),
        /** Dimension icon option. */
        DimensionIconOption("brush", Icons.Outlined.Brush),
        /** Dimension icon option. */
        DimensionIconOption("build", Icons.Outlined.Build),
        /** Dimension icon option. */
        DimensionIconOption("palette", Icons.Outlined.Palette),
        /** Dimension icon option. */
        DimensionIconOption("bookmark_border", Icons.Outlined.BookmarkBorder),
        /** Dimension icon option. */
        DimensionIconOption("lightbulb", Icons.Outlined.Lightbulb),
        /** Dimension icon option. */
        DimensionIconOption("widgets", Icons.Outlined.Widgets),
        /** Dimension icon option. */
        DimensionIconOption("rocket_launch", Icons.Outlined.RocketLaunch),
        /** Dimension icon option. */
        DimensionIconOption("sports_soccer", Icons.Outlined.SportsSoccer),
        /** Dimension icon option. */
        DimensionIconOption("headphones", Icons.Outlined.Headphones),
        /** Dimension icon option. */
        DimensionIconOption("photo_camera", Icons.Outlined.PhotoCamera),
        /** Dimension icon option. */
        DimensionIconOption("science", Icons.Outlined.Science),
        /** Dimension icon option. */
        DimensionIconOption("medical_services", Icons.Outlined.MedicalServices),
        /** Dimension icon option. */
        DimensionIconOption("monitor_heart", Icons.Outlined.MonitorHeart),
        /** Dimension icon option. */
        DimensionIconOption("temple_hindu", Icons.Outlined.TempleHindu),
        /** Dimension icon option. */
        DimensionIconOption("forest", Icons.Outlined.Forest),
        /** Dimension icon option. */
        DimensionIconOption("park", Icons.Outlined.Park),
        /** Dimension icon option. */
        DimensionIconOption("wb_sunny", Icons.Outlined.WbSunny),
        /** Dimension icon option. */
        DimensionIconOption("dark_mode", Icons.Outlined.DarkMode),
        /** Dimension icon option. */
        DimensionIconOption("keyboard", Icons.Outlined.Keyboard),
        /** Dimension icon option. */
        DimensionIconOption("shopping_bag", Icons.Outlined.ShoppingBag),
        /** Dimension icon option. */
        DimensionIconOption("payments", Icons.Outlined.Payments),
        /** Dimension icon option. */
        DimensionIconOption("storefront", Icons.Outlined.Storefront),
        /** Dimension icon option. */
        DimensionIconOption("flight", Icons.Outlined.Flight),
        /** Dimension icon option. */
        DimensionIconOption("ramen_dining", Icons.Outlined.RamenDining),
        /** Dimension icon option. */
        DimensionIconOption("lunch_dining", Icons.Outlined.LunchDining),
        /** Dimension icon option. */
        DimensionIconOption("shopping_cart", Icons.Outlined.ShoppingCart),
        /** Dimension icon option. */
        DimensionIconOption("local_florist", Icons.Outlined.LocalFlorist),
        /** Dimension icon option. */
        DimensionIconOption("spa", Icons.Outlined.Spa),
        /** Dimension icon option. */
        DimensionIconOption("theater_comedy", Icons.Outlined.TheaterComedy),
        /** Dimension icon option. */
        DimensionIconOption("toys", Icons.Outlined.Toys),
        /** Dimension icon option. */
        DimensionIconOption("mood", Icons.Outlined.Mood),
        /** Dimension icon option. */
        DimensionIconOption("heart_broken", Icons.Outlined.HeartBroken),
        /** Dimension icon option. */
        DimensionIconOption("help_outline", Icons.Outlined.HelpOutline),
        /** Dimension icon option. */
        DimensionIconOption("cleaning_services", Icons.Outlined.CleaningServices),
        /** Dimension icon option. */
        DimensionIconOption("inventory_2", Icons.Outlined.Inventory2),
        /** Dimension icon option. */
        DimensionIconOption("language", Icons.Outlined.Language),
        /** Dimension icon option. */
        DimensionIconOption("lock", Icons.Outlined.Lock),
        /** Dimension icon option. */
        DimensionIconOption("handyman", Icons.Outlined.Handyman),
        /** Dimension icon option. */
        DimensionIconOption("edit_note", Icons.Outlined.EditNote),
        /** Dimension icon option. */
        DimensionIconOption("piano", Icons.Outlined.Piano),
        /** Dimension icon option. */
        DimensionIconOption("explore", Icons.Outlined.Explore),
        /** Dimension icon option. */
        DimensionIconOption("diversity_3", Icons.Outlined.Diversity3),
        /** Dimension icon option. */
        DimensionIconOption("volcano", Icons.Outlined.Volcano),
        /** Dimension icon option. */
        DimensionIconOption("abc", Icons.Outlined.Abc),
        /** Dimension icon option. */
        DimensionIconOption("access_alarm", Icons.Outlined.AccessAlarm),
        /** Dimension icon option. */
        DimensionIconOption("access_alarms", Icons.Outlined.AccessAlarms),
        /** Dimension icon option. */
        DimensionIconOption("accessibility", Icons.Outlined.Accessibility),
        /** Dimension icon option. */
        DimensionIconOption("accessibility_new", Icons.Outlined.AccessibilityNew),
        /** Dimension icon option. */
        DimensionIconOption("accessible", Icons.Outlined.Accessible),
        /** Dimension icon option. */
        DimensionIconOption("accessible_forward", Icons.Outlined.AccessibleForward),
        /** Dimension icon option. */
        DimensionIconOption("access_time", Icons.Outlined.AccessTime),
        /** Dimension icon option. */
        DimensionIconOption("access_time_filled", Icons.Outlined.AccessTimeFilled),
        /** Dimension icon option. */
        DimensionIconOption("account_tree", Icons.Outlined.AccountTree),
        /** Dimension icon option. */
        DimensionIconOption("ac_unit", Icons.Outlined.AcUnit),
        /** Dimension icon option. */
        DimensionIconOption("adb", Icons.Outlined.Adb),
        /** Dimension icon option. */
        DimensionIconOption("add_alarm", Icons.Outlined.AddAlarm),
        /** Dimension icon option. */
        DimensionIconOption("add_alert", Icons.Outlined.AddAlert),
        /** Dimension icon option. */
        DimensionIconOption("add_a_photo", Icons.Outlined.AddAPhoto),
        /** Dimension icon option. */
        DimensionIconOption("add_box", Icons.Outlined.AddBox),
        /** Dimension icon option. */
        DimensionIconOption("add_business", Icons.Outlined.AddBusiness),
        /** Dimension icon option. */
        DimensionIconOption("add_card", Icons.Outlined.AddCard),
        /** Dimension icon option. */
        DimensionIconOption("addchart", Icons.Outlined.Addchart),
        /** Dimension icon option. */
        DimensionIconOption("add_home", Icons.Outlined.AddHome),
        /** Dimension icon option. */
        DimensionIconOption("add_moderator", Icons.Outlined.AddModerator),
        /** Dimension icon option. */
        DimensionIconOption("add_photo_alternate", Icons.Outlined.AddPhotoAlternate),
        /** Dimension icon option. */
        DimensionIconOption("add_reaction", Icons.Outlined.AddReaction),
        /** Dimension icon option. */
        DimensionIconOption("add_road", Icons.Outlined.AddRoad),
        /** Dimension icon option. */
        DimensionIconOption("add_to_drive", Icons.Outlined.AddToDrive),
        /** Dimension icon option. */
        DimensionIconOption("add_to_photos", Icons.Outlined.AddToPhotos),
        /** Dimension icon option. */
        DimensionIconOption("add_to_queue", Icons.Outlined.AddToQueue),
        /** Dimension icon option. */
        DimensionIconOption("adjust", Icons.Outlined.Adjust),
        /** Dimension icon option. */
        DimensionIconOption("ad_units", Icons.Outlined.AdUnits),
        /** Dimension icon option. */
        DimensionIconOption("agriculture", Icons.Outlined.Agriculture),
        /** Dimension icon option. */
        DimensionIconOption("air", Icons.Outlined.Air),
        /** Dimension icon option. */
        DimensionIconOption("airlines", Icons.Outlined.Airlines),
        /** Dimension icon option. */
        DimensionIconOption("airline_stops", Icons.Outlined.AirlineStops),
        /** Dimension icon option. */
        DimensionIconOption("airport_shuttle", Icons.Outlined.AirportShuttle),
        /** Dimension icon option. */
        DimensionIconOption("alarm", Icons.Outlined.Alarm),
        /** Dimension icon option. */
        DimensionIconOption("alarm_add", Icons.Outlined.AlarmAdd),
        /** Dimension icon option. */
        DimensionIconOption("alarm_off", Icons.Outlined.AlarmOff),
        /** Dimension icon option. */
        DimensionIconOption("alarm_on", Icons.Outlined.AlarmOn),
        /** Dimension icon option. */
        DimensionIconOption("album", Icons.Outlined.Album),
        /** Dimension icon option. */
        DimensionIconOption("align_horizontal_center", Icons.Outlined.AlignHorizontalCenter),
        /** Dimension icon option. */
        DimensionIconOption("align_horizontal_left", Icons.Outlined.AlignHorizontalLeft),
        /** Dimension icon option. */
        DimensionIconOption("align_horizontal_right", Icons.Outlined.AlignHorizontalRight),
        /** Dimension icon option. */
        DimensionIconOption("all_inclusive", Icons.Outlined.AllInclusive),
        /** Dimension icon option. */
        DimensionIconOption("all_out", Icons.Outlined.AllOut),
        /** Dimension icon option. */
        DimensionIconOption("alt_route", Icons.Outlined.AltRoute),
        /** Dimension icon option. */
        DimensionIconOption("amp_stories", Icons.Outlined.AmpStories),
        /** Dimension icon option. */
        DimensionIconOption("analytics", Icons.Outlined.Analytics),
        /** Dimension icon option. */
        DimensionIconOption("anchor", Icons.Outlined.Anchor),
        /** Dimension icon option. */
        DimensionIconOption("android", Icons.Outlined.Android),
        /** Dimension icon option. */
        DimensionIconOption("animation", Icons.Outlined.Animation),
        /** Dimension icon option. */
        DimensionIconOption("announcement", Icons.Outlined.Announcement),
        /** Dimension icon option. */
        DimensionIconOption("aod", Icons.Outlined.Aod),
        /** Dimension icon option. */
        DimensionIconOption("apartment", Icons.Outlined.Apartment),
        /** Dimension icon option. */
        DimensionIconOption("app_blocking", Icons.Outlined.AppBlocking),
        /** Dimension icon option. */
        DimensionIconOption("app_registration", Icons.Outlined.AppRegistration),
        /** Dimension icon option. */
        DimensionIconOption("approval", Icons.Outlined.Approval),
        /** Dimension icon option. */
        DimensionIconOption("architecture", Icons.Outlined.Architecture),
        /** Dimension icon option. */
        DimensionIconOption("area_chart", Icons.Outlined.AreaChart),
        /** Dimension icon option. */
        DimensionIconOption("article", Icons.Outlined.Article),
        /** Dimension icon option. */
        DimensionIconOption("assignment", Icons.Outlined.Assignment),
        /** Dimension icon option. */
        DimensionIconOption("assignment_ind", Icons.Outlined.AssignmentInd),
        /** Dimension icon option. */
        DimensionIconOption("assignment_late", Icons.Outlined.AssignmentLate),
        /** Dimension icon option. */
        DimensionIconOption("assignment_return", Icons.Outlined.AssignmentReturn),
        /** Dimension icon option. */
        DimensionIconOption("assignment_returned", Icons.Outlined.AssignmentReturned),
        /** Dimension icon option. */
        DimensionIconOption("assignment_turned_in", Icons.Outlined.AssignmentTurnedIn),
        /** Dimension icon option. */
        DimensionIconOption("assistant", Icons.Outlined.Assistant),
        /** Dimension icon option. */
        DimensionIconOption("assistant_direction", Icons.Outlined.AssistantDirection),
        /** Dimension icon option. */
        DimensionIconOption("assistant_photo", Icons.Outlined.AssistantPhoto),
        /** Dimension icon option. */
        DimensionIconOption("assist_walker", Icons.Outlined.AssistWalker),
        /** Dimension icon option. */
        DimensionIconOption("assured_workload", Icons.Outlined.AssuredWorkload),
        /** Dimension icon option. */
        DimensionIconOption("atm", Icons.Outlined.Atm),
        /** Dimension icon option. */
        DimensionIconOption("attractions", Icons.Outlined.Attractions),
        /** Dimension icon option. */
        DimensionIconOption("attribution", Icons.Outlined.Attribution),
        /** Dimension icon option. */
        DimensionIconOption("auto_awesome", Icons.Outlined.AutoAwesome),
        /** Dimension icon option. */
        DimensionIconOption("auto_awesome_mosaic", Icons.Outlined.AutoAwesomeMosaic),
        /** Dimension icon option. */
        DimensionIconOption("auto_awesome_motion", Icons.Outlined.AutoAwesomeMotion),
        /** Dimension icon option. */
        DimensionIconOption("auto_delete", Icons.Outlined.AutoDelete),
        /** Dimension icon option. */
        DimensionIconOption("auto_fix_high", Icons.Outlined.AutoFixHigh),
        /** Dimension icon option. */
        DimensionIconOption("auto_fix_normal", Icons.Outlined.AutoFixNormal),
        /** Dimension icon option. */
        DimensionIconOption("auto_fix_off", Icons.Outlined.AutoFixOff),
        /** Dimension icon option. */
        DimensionIconOption("auto_graph", Icons.Outlined.AutoGraph),
        /** Dimension icon option. */
        DimensionIconOption("auto_mode", Icons.Outlined.AutoMode),
        /** Dimension icon option. */
        DimensionIconOption("autorenew", Icons.Outlined.Autorenew),
        /** Dimension icon option. */
        DimensionIconOption("auto_stories", Icons.Outlined.AutoStories),
        /** Dimension icon option. */
        DimensionIconOption("baby_changing_station", Icons.Outlined.BabyChangingStation),
        /** Dimension icon option. */
        DimensionIconOption("badge", Icons.Outlined.Badge),
        /** Dimension icon option. */
        DimensionIconOption("bakery_dining", Icons.Outlined.BakeryDining),
        /** Dimension icon option. */
        DimensionIconOption("balcony", Icons.Outlined.Balcony),
        /** Dimension icon option. */
        DimensionIconOption("ballot", Icons.Outlined.Ballot),
        /** Dimension icon option. */
        DimensionIconOption("bar_chart", Icons.Outlined.BarChart),
        /** Dimension icon option. */
        DimensionIconOption("batch_prediction", Icons.Outlined.BatchPrediction),
        /** Dimension icon option. */
        DimensionIconOption("bathroom", Icons.Outlined.Bathroom),
        /** Dimension icon option. */
        DimensionIconOption("bathtub", Icons.Outlined.Bathtub),
        /** Dimension icon option. */
        DimensionIconOption("beach_access", Icons.Outlined.BeachAccess),
        /** Dimension icon option. */
        DimensionIconOption("bed", Icons.Outlined.Bed),
        /** Dimension icon option. */
        DimensionIconOption("bedroom_baby", Icons.Outlined.BedroomBaby),
        /** Dimension icon option. */
        DimensionIconOption("bedroom_child", Icons.Outlined.BedroomChild),
        /** Dimension icon option. */
        DimensionIconOption("bedroom_parent", Icons.Outlined.BedroomParent),
        /** Dimension icon option. */
        DimensionIconOption("bedtime", Icons.Outlined.Bedtime),
        /** Dimension icon option. */
        DimensionIconOption("bedtime_off", Icons.Outlined.BedtimeOff),
        /** Dimension icon option. */
        DimensionIconOption("beenhere", Icons.Outlined.Beenhere),
        /** Dimension icon option. */
        DimensionIconOption("bento", Icons.Outlined.Bento),
        /** Dimension icon option. */
        DimensionIconOption("bike_scooter", Icons.Outlined.BikeScooter),
        /** Dimension icon option. */
        DimensionIconOption("biotech", Icons.Outlined.Biotech),
        /** Dimension icon option. */
        DimensionIconOption("blender", Icons.Outlined.Blender),
        /** Dimension icon option. */
        DimensionIconOption("blind", Icons.Outlined.Blind),
        /** Dimension icon option. */
        DimensionIconOption("blinds", Icons.Outlined.Blinds),
        /** Dimension icon option. */
        DimensionIconOption("blinds_closed", Icons.Outlined.BlindsClosed),
        /** Dimension icon option. */
        DimensionIconOption("bloodtype", Icons.Outlined.Bloodtype),
        /** Dimension icon option. */
        DimensionIconOption("blur_circular", Icons.Outlined.BlurCircular),
        /** Dimension icon option. */
        DimensionIconOption("blur_linear", Icons.Outlined.BlurLinear),
        /** Dimension icon option. */
        DimensionIconOption("blur_off", Icons.Outlined.BlurOff),
        /** Dimension icon option. */
        DimensionIconOption("blur_on", Icons.Outlined.BlurOn),
        /** Dimension icon option. */
        DimensionIconOption("bookmark_add", Icons.Outlined.BookmarkAdd),
        /** Dimension icon option. */
        DimensionIconOption("bookmark_added", Icons.Outlined.BookmarkAdded),
        /** Dimension icon option. */
        DimensionIconOption("bookmark_remove", Icons.Outlined.BookmarkRemove),
        /** Dimension icon option. */
        DimensionIconOption("bookmarks", Icons.Outlined.Bookmarks),
        /** Dimension icon option. */
        DimensionIconOption("book_online", Icons.Outlined.BookOnline),
        /** Dimension icon option. */
        DimensionIconOption("border_all", Icons.Outlined.BorderAll),
        /** Dimension icon option. */
        DimensionIconOption("border_bottom", Icons.Outlined.BorderBottom),
        /** Dimension icon option. */
        DimensionIconOption("border_clear", Icons.Outlined.BorderClear),
        /** Dimension icon option. */
        DimensionIconOption("border_color", Icons.Outlined.BorderColor),
        /** Dimension icon option. */
        DimensionIconOption("border_horizontal", Icons.Outlined.BorderHorizontal),
        /** Dimension icon option. */
        DimensionIconOption("border_inner", Icons.Outlined.BorderInner),
        /** Dimension icon option. */
        DimensionIconOption("border_left", Icons.Outlined.BorderLeft),
        /** Dimension icon option. */
        DimensionIconOption("border_right", Icons.Outlined.BorderRight),
        /** Dimension icon option. */
        DimensionIconOption("border_style", Icons.Outlined.BorderStyle),
        /** Dimension icon option. */
        DimensionIconOption("border_top", Icons.Outlined.BorderTop),
        /** Dimension icon option. */
        DimensionIconOption("boy", Icons.Outlined.Boy),
        /** Dimension icon option. */
        DimensionIconOption("breakfast_dining", Icons.Outlined.BreakfastDining),
        /** Dimension icon option. */
        DimensionIconOption("brightness1", Icons.Outlined.Brightness1),
        /** Dimension icon option. */
        DimensionIconOption("brightness2", Icons.Outlined.Brightness2),
        /** Dimension icon option. */
        DimensionIconOption("brightness3", Icons.Outlined.Brightness3),
        /** Dimension icon option. */
        DimensionIconOption("brightness4", Icons.Outlined.Brightness4),
        /** Dimension icon option. */
        DimensionIconOption("brightness5", Icons.Outlined.Brightness5),
        /** Dimension icon option. */
        DimensionIconOption("brightness6", Icons.Outlined.Brightness6),
        /** Dimension icon option. */
        DimensionIconOption("brightness7", Icons.Outlined.Brightness7),
        /** Dimension icon option. */
        DimensionIconOption("brightness_auto", Icons.Outlined.BrightnessAuto),
        /** Dimension icon option. */
        DimensionIconOption("brightness_high", Icons.Outlined.BrightnessHigh),
        /** Dimension icon option. */
        DimensionIconOption("brightness_low", Icons.Outlined.BrightnessLow),
        /** Dimension icon option. */
        DimensionIconOption("brightness_medium", Icons.Outlined.BrightnessMedium),
        /** Dimension icon option. */
        DimensionIconOption("brunch_dining", Icons.Outlined.BrunchDining),
        /** Dimension icon option. */
        DimensionIconOption("bubble_chart", Icons.Outlined.BubbleChart),
        /** Dimension icon option. */
        DimensionIconOption("bungalow", Icons.Outlined.Bungalow),
        /** Dimension icon option. */
        DimensionIconOption("burst_mode", Icons.Outlined.BurstMode),
        /** Dimension icon option. */
        DimensionIconOption("bus_alert", Icons.Outlined.BusAlert),
        /** Dimension icon option. */
        DimensionIconOption("business", Icons.Outlined.Business),
        /** Dimension icon option. */
        DimensionIconOption("business_center", Icons.Outlined.BusinessCenter),
        /** Dimension icon option. */
        DimensionIconOption("cabin", Icons.Outlined.Cabin),
        /** Dimension icon option. */
        DimensionIconOption("cable", Icons.Outlined.Cable),
        /** Dimension icon option. */
        DimensionIconOption("cached", Icons.Outlined.Cached),
        /** Dimension icon option. */
        DimensionIconOption("cake", Icons.Outlined.Cake),
        /** Dimension icon option. */
        DimensionIconOption("calculate", Icons.Outlined.Calculate),
        /** Dimension icon option. */
        DimensionIconOption("calendar_month", Icons.Outlined.CalendarMonth),
        /** Dimension icon option. */
        DimensionIconOption("calendar_today", Icons.Outlined.CalendarToday),
        /** Dimension icon option. */
        DimensionIconOption("camera", Icons.Outlined.Camera),
        /** Dimension icon option. */
        DimensionIconOption("camera_alt", Icons.Outlined.CameraAlt),
        /** Dimension icon option. */
        DimensionIconOption("camera_enhance", Icons.Outlined.CameraEnhance),
        /** Dimension icon option. */
        DimensionIconOption("camera_front", Icons.Outlined.CameraFront),
        /** Dimension icon option. */
        DimensionIconOption("camera_rear", Icons.Outlined.CameraRear),
        /** Dimension icon option. */
        DimensionIconOption("camera_roll", Icons.Outlined.CameraRoll),
        /** Dimension icon option. */
        DimensionIconOption("cameraswitch", Icons.Outlined.Cameraswitch),
        /** Dimension icon option. */
        DimensionIconOption("cancel", Icons.Outlined.Cancel),
        /** Dimension icon option. */
        DimensionIconOption("cancel_presentation", Icons.Outlined.CancelPresentation),
        /** Dimension icon option. */
        DimensionIconOption("candlestick_chart", Icons.Outlined.CandlestickChart),
        /** Dimension icon option. */
        DimensionIconOption("car_crash", Icons.Outlined.CarCrash),
        /** Dimension icon option. */
        DimensionIconOption("card_membership", Icons.Outlined.CardMembership),
        /** Dimension icon option. */
        DimensionIconOption("card_travel", Icons.Outlined.CardTravel),
        /** Dimension icon option. */
        DimensionIconOption("carpenter", Icons.Outlined.Carpenter),
        /** Dimension icon option. */
        DimensionIconOption("car_rental", Icons.Outlined.CarRental),
        /** Dimension icon option. */
        DimensionIconOption("car_repair", Icons.Outlined.CarRepair),
        /** Dimension icon option. */
        DimensionIconOption("cases", Icons.Outlined.Cases),
        /** Dimension icon option. */
        DimensionIconOption("casino", Icons.Outlined.Casino),
        /** Dimension icon option. */
        DimensionIconOption("catching_pokemon", Icons.Outlined.CatchingPokemon),
        /** Dimension icon option. */
        DimensionIconOption("celebration", Icons.Outlined.Celebration),
        /** Dimension icon option. */
        DimensionIconOption("center_focus_strong", Icons.Outlined.CenterFocusStrong),
        /** Dimension icon option. */
        DimensionIconOption("chair", Icons.Outlined.Chair),
        /** Dimension icon option. */
        DimensionIconOption("chair_alt", Icons.Outlined.ChairAlt),
        /** Dimension icon option. */
        DimensionIconOption("chalet", Icons.Outlined.Chalet),
        /** Dimension icon option. */
        DimensionIconOption("change_history", Icons.Outlined.ChangeHistory),
        /** Dimension icon option. */
        DimensionIconOption("chat", Icons.Outlined.Chat),
        /** Dimension icon option. */
        DimensionIconOption("chat_bubble", Icons.Outlined.ChatBubble),
        /** Dimension icon option. */
        DimensionIconOption("chat_bubble_outline", Icons.Outlined.ChatBubbleOutline),
        /** Dimension icon option. */
        DimensionIconOption("chevron_left", Icons.Outlined.ChevronLeft),
        /** Dimension icon option. */
        DimensionIconOption("chevron_right", Icons.Outlined.ChevronRight),
        /** Dimension icon option. */
        DimensionIconOption("child_friendly", Icons.Outlined.ChildFriendly),
        /** Dimension icon option. */
        DimensionIconOption("chrome_reader_mode", Icons.Outlined.ChromeReaderMode),
        /** Dimension icon option. */
        DimensionIconOption("church", Icons.Outlined.Church),
        /** Dimension icon option. */
        DimensionIconOption("class", Icons.Outlined.Class),
        /** Dimension icon option. */
        DimensionIconOption("clean_hands", Icons.Outlined.CleanHands),
        /** Dimension icon option. */
        DimensionIconOption("clear_all", Icons.Outlined.ClearAll),
        /** Dimension icon option. */
        DimensionIconOption("co2", Icons.Outlined.Co2),
        /** Dimension icon option. */
        DimensionIconOption("coffee", Icons.Outlined.Coffee),
        /** Dimension icon option. */
        DimensionIconOption("coffee_maker", Icons.Outlined.CoffeeMaker),
        /** Dimension icon option. */
        DimensionIconOption("collections", Icons.Outlined.Collections),
        /** Dimension icon option. */
        DimensionIconOption("colorize", Icons.Outlined.Colorize),
        /** Dimension icon option. */
        DimensionIconOption("color_lens", Icons.Outlined.ColorLens),
        /** Dimension icon option. */
        DimensionIconOption("commit", Icons.Outlined.Commit),
        /** Dimension icon option. */
        DimensionIconOption("commute", Icons.Outlined.Commute),
        /** Dimension icon option. */
        DimensionIconOption("compare", Icons.Outlined.Compare),
        /** Dimension icon option. */
        DimensionIconOption("compost", Icons.Outlined.Compost),
        /** Dimension icon option. */
        DimensionIconOption("confirmation_number", Icons.Outlined.ConfirmationNumber),
        /** Dimension icon option. */
        DimensionIconOption("connecting_airports", Icons.Outlined.ConnectingAirports),
        /** Dimension icon option. */
        DimensionIconOption("connect_without_contact", Icons.Outlined.ConnectWithoutContact),
        /** Dimension icon option. */
        DimensionIconOption("construction", Icons.Outlined.Construction),
        /** Dimension icon option. */
        DimensionIconOption("contact_emergency", Icons.Outlined.ContactEmergency),
        /** Dimension icon option. */
        DimensionIconOption("contactless", Icons.Outlined.Contactless),
        /** Dimension icon option. */
        DimensionIconOption("contact_page", Icons.Outlined.ContactPage),
        /** Dimension icon option. */
        DimensionIconOption("contacts", Icons.Outlined.Contacts),
        /** Dimension icon option. */
        DimensionIconOption("contact_support", Icons.Outlined.ContactSupport),
        /** Dimension icon option. */
        DimensionIconOption("content_copy", Icons.Outlined.ContentCopy),
        /** Dimension icon option. */
        DimensionIconOption("content_cut", Icons.Outlined.ContentCut),
        /** Dimension icon option. */
        DimensionIconOption("content_paste", Icons.Outlined.ContentPaste),
        /** Dimension icon option. */
        DimensionIconOption("content_paste_go", Icons.Outlined.ContentPasteGo),
        /** Dimension icon option. */
        DimensionIconOption("content_paste_off", Icons.Outlined.ContentPasteOff),
        /** Dimension icon option. */
        DimensionIconOption("contrast", Icons.Outlined.Contrast),
        /** Dimension icon option. */
        DimensionIconOption("control_camera", Icons.Outlined.ControlCamera),
        /** Dimension icon option. */
        DimensionIconOption("control_point", Icons.Outlined.ControlPoint),
        /** Dimension icon option. */
        DimensionIconOption("control_point_duplicate", Icons.Outlined.ControlPointDuplicate),
        /** Dimension icon option. */
        DimensionIconOption("cookie", Icons.Outlined.Cookie),
        /** Dimension icon option. */
        DimensionIconOption("co_present", Icons.Outlined.CoPresent),
        /** Dimension icon option. */
        DimensionIconOption("copy_all", Icons.Outlined.CopyAll),
        /** Dimension icon option. */
        DimensionIconOption("copyright", Icons.Outlined.Copyright),
        /** Dimension icon option. */
        DimensionIconOption("coronavirus", Icons.Outlined.Coronavirus),
        /** Dimension icon option. */
        DimensionIconOption("corporate_fare", Icons.Outlined.CorporateFare),
        /** Dimension icon option. */
        DimensionIconOption("countertops", Icons.Outlined.Countertops),
        /** Dimension icon option. */
        DimensionIconOption("credit_card", Icons.Outlined.CreditCard),
        /** Dimension icon option. */
        DimensionIconOption("crib", Icons.Outlined.Crib),
        /** Dimension icon option. */
        DimensionIconOption("crisis_alert", Icons.Outlined.CrisisAlert),
        /** Dimension icon option. */
        DimensionIconOption("cruelty_free", Icons.Outlined.CrueltyFree),
        /** Dimension icon option. */
        DimensionIconOption("css", Icons.Outlined.Css),
        /** Dimension icon option. */
        DimensionIconOption("currency_bitcoin", Icons.Outlined.CurrencyBitcoin),
        /** Dimension icon option. */
        DimensionIconOption("currency_exchange", Icons.Outlined.CurrencyExchange),
        /** Dimension icon option. */
        DimensionIconOption("currency_franc", Icons.Outlined.CurrencyFranc),
        /** Dimension icon option. */
        DimensionIconOption("currency_lira", Icons.Outlined.CurrencyLira),
        /** Dimension icon option. */
        DimensionIconOption("currency_pound", Icons.Outlined.CurrencyPound),
        /** Dimension icon option. */
        DimensionIconOption("currency_ruble", Icons.Outlined.CurrencyRuble),
        /** Dimension icon option. */
        DimensionIconOption("currency_rupee", Icons.Outlined.CurrencyRupee),
        /** Dimension icon option. */
        DimensionIconOption("currency_yen", Icons.Outlined.CurrencyYen),
        /** Dimension icon option. */
        DimensionIconOption("currency_yuan", Icons.Outlined.CurrencyYuan),
        /** Dimension icon option. */
        DimensionIconOption("curtains", Icons.Outlined.Curtains),
        /** Dimension icon option. */
        DimensionIconOption("curtains_closed", Icons.Outlined.CurtainsClosed),
        /** Dimension icon option. */
        DimensionIconOption("cyclone", Icons.Outlined.Cyclone),
        /** Dimension icon option. */
        DimensionIconOption("dangerous", Icons.Outlined.Dangerous),
        /** Dimension icon option. */
        DimensionIconOption("dashboard", Icons.Outlined.Dashboard),
        /** Dimension icon option. */
        DimensionIconOption("dashboard_customize", Icons.Outlined.DashboardCustomize),
        /** Dimension icon option. */
        DimensionIconOption("deblur", Icons.Outlined.Deblur),
        /** Dimension icon option. */
        DimensionIconOption("dehaze", Icons.Outlined.Dehaze),
        /** Dimension icon option. */
        DimensionIconOption("delete_forever", Icons.Outlined.DeleteForever),
        /** Dimension icon option. */
        DimensionIconOption("delete_outline", Icons.Outlined.DeleteOutline),
        /** Dimension icon option. */
        DimensionIconOption("delete_sweep", Icons.Outlined.DeleteSweep),
        /** Dimension icon option. */
        DimensionIconOption("density_large", Icons.Outlined.DensityLarge),
        /** Dimension icon option. */
        DimensionIconOption("density_medium", Icons.Outlined.DensityMedium),
        /** Dimension icon option. */
        DimensionIconOption("density_small", Icons.Outlined.DensitySmall),
        /** Dimension icon option. */
        DimensionIconOption("departure_board", Icons.Outlined.DepartureBoard),
        /** Dimension icon option. */
        DimensionIconOption("description", Icons.Outlined.Description),
        /** Dimension icon option. */
        DimensionIconOption("design_services", Icons.Outlined.DesignServices),
        /** Dimension icon option. */
        DimensionIconOption("desktop_mac", Icons.Outlined.DesktopMac),
        /** Dimension icon option. */
        DimensionIconOption("details", Icons.Outlined.Details),
        /** Dimension icon option. */
        DimensionIconOption("developer_board", Icons.Outlined.DeveloperBoard),
        /** Dimension icon option. */
        DimensionIconOption("developer_board_off", Icons.Outlined.DeveloperBoardOff),
        /** Dimension icon option. */
        DimensionIconOption("devices", Icons.Outlined.Devices),
        /** Dimension icon option. */
        DimensionIconOption("devices_fold", Icons.Outlined.DevicesFold),
        /** Dimension icon option. */
        DimensionIconOption("devices_other", Icons.Outlined.DevicesOther),
        /** Dimension icon option. */
        DimensionIconOption("device_thermostat", Icons.Outlined.DeviceThermostat),
        /** Dimension icon option. */
        DimensionIconOption("device_unknown", Icons.Outlined.DeviceUnknown),
        /** Dimension icon option. */
        DimensionIconOption("dialpad", Icons.Outlined.Dialpad),
        /** Dimension icon option. */
        DimensionIconOption("diamond", Icons.Outlined.Diamond),
        /** Dimension icon option. */
        DimensionIconOption("difference", Icons.Outlined.Difference),
        /** Dimension icon option. */
        DimensionIconOption("dining", Icons.Outlined.Dining),
        /** Dimension icon option. */
        DimensionIconOption("dinner_dining", Icons.Outlined.DinnerDining),
        /** Dimension icon option. */
        DimensionIconOption("directions", Icons.Outlined.Directions),
        /** Dimension icon option. */
        DimensionIconOption("directions_bike", Icons.Outlined.DirectionsBike),
        /** Dimension icon option. */
        DimensionIconOption("directions_boat", Icons.Outlined.DirectionsBoat),
        /** Dimension icon option. */
        DimensionIconOption("directions_boat_filled", Icons.Outlined.DirectionsBoatFilled),
        /** Dimension icon option. */
        DimensionIconOption("directions_bus", Icons.Outlined.DirectionsBus),
        /** Dimension icon option. */
        DimensionIconOption("directions_bus_filled", Icons.Outlined.DirectionsBusFilled),
        /** Dimension icon option. */
        DimensionIconOption("directions_car", Icons.Outlined.DirectionsCar),
        /** Dimension icon option. */
        DimensionIconOption("directions_walk", Icons.Outlined.DirectionsWalk),
    )

    private val optionsByKey: Map<String, DimensionIconOption> = options.associateBy { it.key }
    private val keyAliases: Map<String, String> = mapOf("people" to "groups")

    /**
     * Default icon key for dimension id.
     */
    fun defaultIconKeyForDimensionId(dimensionId: String?): String {
        /** Normalized id. */
        val normalizedId = dimensionId?.trim().orEmpty()
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(normalizedId)?.id
        DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.let { return it.defaultIconKey }
        return when (dimensionId) {
            LifeDimension.CAREER_WORK.id -> "work"
            LifeDimension.HEALTH_WELLNESS.id -> "favorite"
            LifeDimension.RELATIONSHIPS.id -> "groups"
            LifeDimension.PERSONAL_GROWTH.id -> "trending_up"
            LifeDimension.FINANCIAL.id -> "account_balance_wallet"
            LifeDimension.SPIRITUAL.id -> "self_improvement"
            LifeDimension.RECREATION.id -> "sports_esports"
            LifeDimension.LEARNING.id -> "menu_book"
            LifeDimension.CONTRIBUTION.id -> "volunteer_activism"
            else -> "category"
        }
    }

    /**
     * Resolve.
     */
    fun resolve(key: String?, dimensionId: String? = null): DimensionIconOption {
        /** Normalized. */
        val normalized = key?.trim()?.takeIf { it.isNotEmpty() } ?: defaultIconKeyForDimensionId(dimensionId)
        /** Canonical key. */
        val canonicalKey = keyAliases[normalized] ?: normalized
        return optionsByKey[canonicalKey] ?: run {
            /** Fallback key. */
            val fallbackKey = defaultIconKeyForDimensionId(dimensionId)
            /** Logger or null. */
            loggerOrNull()?.w(
                "DimensionIconCatalog.resolve",
                "Unknown dimension icon key; using fallback",
                /** Map of. */
                mapOf("requestedKey" to normalized, "fallbackKey" to fallbackKey, "dimensionId" to (dimensionId ?: "none")),
            )
            optionsByKey.getValue(fallbackKey)
        }
    }
}
