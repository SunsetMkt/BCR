package com.chiller3.bcr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.database.getStringOrNull
import java.text.ParsePosition
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.time.temporal.Temporal

data class OutputFilename(
    val value: String,
    val redacted: String,
) {
    override fun toString() = redacted
}

/**
 * Helper class for determining a recording's output filename based on information from a call.
 */
class OutputFilenameGenerator(
    private val context: Context,
    private val parentCall: Call,
) {
    // Templates
    private val filenameTemplate = Preferences(context).filenameTemplate
        ?: Preferences.DEFAULT_FILENAME_TEMPLATE
    private val dateVarLocations = filenameTemplate.findVariableRef(DATE_VAR)?.third

    // Call information
    private val callDetails = mutableMapOf<Call, Call.Details>()
    private val isConference = parentCall.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)

    // Timestamps
    private lateinit var _callTimestamp: ZonedDateTime
    val callTimestamp: ZonedDateTime
        get() = synchronized(this) {
            _callTimestamp
        }
    private var formatter = FORMATTER

    // Redactions
    private val redactions = HashMap<String, String>()
    private val redactionsSorted = mutableListOf<Pair<String, String>>()
    val redactor = object : OutputDirUtils.Redactor {
        override fun redact(msg: String): String {
            synchronized(this@OutputFilenameGenerator) {
                var result = msg

                for ((source, target) in redactionsSorted) {
                    result = result.replace(source, target)
                }

                return result
            }
        }

        override fun redact(uri: Uri): String = redact(Uri.decode(uri.toString()))
    }

    private lateinit var _filename: OutputFilename
    val filename: OutputFilename
        get() = synchronized(this) {
            _filename
        }

    init {
        Log.i(TAG, "Filename template: $filenameTemplate")

        callDetails[parentCall] = parentCall.details
        if (isConference) {
            for (childCall in parentCall.children) {
                callDetails[childCall] = childCall.details
            }
        }

        update(false)
    }

    /**
     * Update [filename] with information from [details].
     *
     * @param call Either the parent call or a child of the parent (for conference calls)
     * @param details The updated call details belonging to [call]
     */
    fun updateCallDetails(call: Call, details: Call.Details) {
        if (call !== parentCall && call.parent !== parentCall) {
            throw IllegalStateException("Not the parent call nor one of its children: $call")
        }

        synchronized(this) {
            callDetails[call] = details

            update(false)
        }
    }

    private fun addRedaction(source: String, target: String) {
        synchronized(this) {
            redactions[source] = target

            // Keyword-based redaction with arbitrary filenames can never be 100% foolproof, but we
            // can improve the odds by replacing the longest strings first
            redactionsSorted.clear()
            redactions.entries
                .mapTo(redactionsSorted) { it.key to it.value }
                .sortByDescending { it.first.length }
        }
    }

    private fun getContactDisplayName(details: Call.Details, allowManualLookup: Boolean): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val name = details.contactDisplayName
            if (name != null) {
                return name
            }
        }

        // In conference calls, the telephony framework sometimes doesn't return the contact display
        // name for every party in the call, so do the lookup ourselves. This is similar to what
        // InCallUI does, except it doesn't even try to look at contactDisplayName.
        if (isConference) {
            Log.w(TAG, "Contact display name missing in conference child call")
        }

        // This is disabled until the very last filename update because it's synchronous.
        if (!allowManualLookup) {
            Log.d(TAG, "Manual lookup is disabled for this invocation")
            return null
        }

        if (!Permissions.isGranted(context, Manifest.permission.READ_CONTACTS)) {
            Log.w(TAG, "Permissions not granted for looking up contacts")
            return null
        }

        Log.d(TAG, "Performing manual contact lookup")

        val number = getPhoneNumber(details)
        if (number == null) {
            Log.w(TAG, "Cannot determine phone number from call")
            return null
        }

        // Same heuristic as InCallUI's PhoneNumberHelper.isUriNumber()
        val numberIsSip = number.contains("@") || number.contains("%40")

        val uri = ContactsContract.PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon()
            .appendPath(number)
            .appendQueryParameter(
                ContactsContract.PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS,
                numberIsSip.toString())
            .build()

        context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (index != -1) {
                    Log.d(TAG, "Found contact display name via manual lookup")
                    return cursor.getStringOrNull(index)
                }
            }
        }

        Log.d(TAG, "Contact not found via manual lookup")
        return null
    }

    private fun generate(template: Template, allowBlockingCalls: Boolean): OutputFilename {
        synchronized(this) {
            val parentDetails = callDetails[parentCall]!!
            val displayDetails = if (isConference) {
                callDetails.entries.asSequence()
                    .filter { it.key != parentCall }
                    .map { it.value }
                    .toList()
            } else {
                listOf(parentDetails)
            }

            val newFilename = template.evaluate { name, arg ->
                when (name) {
                    "date" -> {
                        val instant = Instant.ofEpochMilli(parentDetails.creationTimeMillis)
                        _callTimestamp = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())

                        if (arg != null) {
                            Log.d(TAG, "Using custom datetime pattern: $arg")

                            try {
                                formatter = DateTimeFormatterBuilder()
                                    .appendPattern(arg)
                                    .toFormatter()
                            } catch (e: Exception) {
                                Log.w(TAG, "Invalid custom datetime pattern: $arg; using default", e)
                            }
                        }

                        return@evaluate formatter.format(_callTimestamp)
                    }
                    "direction" -> {
                        // AOSP's telephony framework has internal documentation that specifies that
                        // the call direction is meaningless for conference calls until enough
                        // participants hang up that it becomes an emulated one-on-one call.
                        if (isConference) {
                            return@evaluate "conference"
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            when (parentDetails.callDirection) {
                                Call.Details.DIRECTION_INCOMING -> return@evaluate "in"
                                Call.Details.DIRECTION_OUTGOING -> return@evaluate "out"
                                Call.Details.DIRECTION_UNKNOWN -> {}
                            }
                        }
                    }
                    "sim_slot" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                            == PackageManager.PERMISSION_GRANTED
                            && context.packageManager.hasSystemFeature(
                                PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) {
                            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

                            // Only append SIM slot ID if the device has multiple active SIMs
                            if (subscriptionManager.activeSubscriptionInfoCount > 1) {
                                val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                                val subscriptionId = telephonyManager.getSubscriptionId(parentDetails.accountHandle)
                                val subscriptionInfo = subscriptionManager.getActiveSubscriptionInfo(subscriptionId)

                                return@evaluate "${subscriptionInfo.simSlotIndex + 1}"
                            }
                        }
                    }
                    "phone_number" -> {
                        val joined = displayDetails.asSequence()
                            .map { d -> getPhoneNumber(d) }
                            .filterNotNull()
                            .joinToString(",")
                        if (joined.isNotEmpty()) {
                            addRedaction(joined, if (isConference) {
                                "<conference phone numbers>"
                            } else {
                                "<phone number>"
                            })

                            return@evaluate joined
                        }
                    }
                    "caller_name" -> {
                        val joined = displayDetails.asSequence()
                            .map { d -> d.callerDisplayName?.trim() }
                            .filter { n -> !n.isNullOrEmpty() }
                            .joinToString(",")
                        if (joined.isNotEmpty()) {
                            addRedaction(joined, if (isConference) {
                                "<conference caller names>"
                            } else {
                                "<caller name>"
                            })

                            return@evaluate joined
                        }
                    }
                    "contact_name" -> {
                        val joined = displayDetails.asSequence()
                            .map { d -> getContactDisplayName(d, allowBlockingCalls)?.trim() }
                            .filter { n -> !n.isNullOrEmpty() }
                            .joinToString(",")
                        if (joined.isNotEmpty()) {
                            addRedaction(joined, if (isConference) {
                                "<conference contact names>"
                            } else {
                                "<contact name>"
                            })

                            return@evaluate joined
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown filename template variable: $name")
                    }
                }

                null
            }
                // AOSP's SAF automatically replaces invalid characters with underscores, but just in
                // case an OEM fork breaks that, do the replacement ourselves to prevent directory
                // traversal attacks.
                .replace('/', '_').trim()

            return OutputFilename(newFilename, redactor.redact(newFilename))
        }
    }

    fun update(allowBlockingCalls: Boolean): OutputFilename {
        synchronized(this) {
            _filename = try {
                generate(filenameTemplate, allowBlockingCalls)
            } catch (e: Exception) {
                if (filenameTemplate === Preferences.DEFAULT_FILENAME_TEMPLATE) {
                    throw e
                } else {
                    Log.w(TAG, "Failed to evaluate custom template: $filenameTemplate", e)
                    generate(Preferences.DEFAULT_FILENAME_TEMPLATE, allowBlockingCalls)
                }
            }

            Log.i(TAG, "Updated filename: $_filename")

            return _filename
        }
    }

    private fun parseTimestamp(input: String, startPos: Int): Temporal? {
        val pos = ParsePosition(startPos)
        val parsed = formatter.parse(input, pos)

        return try {
            parsed.query(ZonedDateTime::from)
        } catch (e: DateTimeException) {
            try {
                // A custom pattern might not specify the time zone
                parsed.query(LocalDateTime::from)
            } catch (e: DateTimeException) {
                // A custom pattern might only specify a date with no time
                parsed.query(LocalDate::from).atStartOfDay()
            }
        }
    }

    private fun parseTimestamp(input: String): Temporal? {
        if (dateVarLocations != null) {
            for (location in dateVarLocations) {
                when (location) {
                    is Template.VariableRefLocation.AfterPrefix -> {
                        var searchIndex = 0

                        while (true) {
                            val literalPos = input.indexOf(location.literal, searchIndex)
                            if (literalPos < 0) {
                                break
                            }

                            val timestampPos = literalPos + location.literal.length

                            try {
                                return parseTimestamp(input, timestampPos)
                            } catch (e: DateTimeParseException) {
                                // Ignore
                            } catch (e: DateTimeException) {
                                Log.w(TAG, "Unexpected non-DateTimeParseException error", e)
                            }

                            if (location.atStart) {
                                break
                            } else {
                                searchIndex = timestampPos
                            }
                        }
                    }
                    Template.VariableRefLocation.Arbitrary -> {
                        Log.d(TAG, "Date might be at an arbitrary location")
                    }
                }
            }
        }

        return null
    }

    fun parseTimestampFromFilename(name: String): Temporal? {
        val redacted = redactTruncate(name)
        val timestamp = parseTimestamp(name)

        Log.d(TAG, "Parsed $timestamp from $redacted")

        return timestamp
    }

    companion object {
        private val TAG = OutputFilenameGenerator::class.java.simpleName

        val DATE_VAR = "date"
        val KNOWN_VARS = arrayOf(
            DATE_VAR,
            "direction",
            "sim_slot",
            "phone_number",
            "caller_name",
            "contact_name",
        )

        // Eg. 20220429_180249.123-0400
        private val FORMATTER = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendOffset("+HHMMss", "+0000")
            .toFormatter()

        private fun getPhoneNumber(details: Call.Details): String? {
            val uri = details.handle

            return if (uri?.scheme == PhoneAccount.SCHEME_TEL) {
                uri.schemeSpecificPart
            } else {
                null
            }
        }

        fun redactTruncate(msg: String): String = buildString {
            val n = 2

            if (msg.length > 2 * n) {
                append(msg.substring(0, n))
            }
            append("<...>")
            if (msg.length > 2 * n) {
                append(msg.substring(msg.length - n))
            }
        }
    }
}