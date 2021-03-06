package com.supercilex.robotscouter.feature.teams

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewStub
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.supercilex.robotscouter.TeamSelectionListener
import com.supercilex.robotscouter.core.data.getScoutBundle
import com.supercilex.robotscouter.core.model.Team
import com.supercilex.robotscouter.core.ui.animatePopReveal
import com.supercilex.robotscouter.core.ui.setOnLongClickListenerCompat
import com.supercilex.robotscouter.core.ui.views.ContentLoadingProgressBar
import com.supercilex.robotscouter.core.unsafeLazy
import com.supercilex.robotscouter.shared.TeamDetailsDialog
import kotlinx.android.extensions.LayoutContainer
import kotlinx.android.synthetic.main.team_list_row_layout.*
import org.jetbrains.anko.find
import java.lang.ref.WeakReference
import java.util.Locale
import com.supercilex.robotscouter.R as RC

internal class TeamViewHolder(
        override val containerView: View,
        private val fragment: Fragment,
        private val recyclerView: RecyclerView
) : RecyclerView.ViewHolder(containerView), LayoutContainer,
        View.OnClickListener, View.OnLongClickListener {
    private val unknownName: String by unsafeLazy {
        itemView.context.getString(R.string.team_unknown_team_title)
    }

    private val mediaLoadProgressListener = object : RequestListener<Drawable> {
        override fun onResourceReady(
                resource: Drawable?,
                model: Any?,
                target: Target<Drawable>,
                dataSource: DataSource,
                isFirstResource: Boolean
        ): Boolean {
            isMediaLoaded = true
            return false
        }

        override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
        ): Boolean {
            isMediaLoaded = true
            return false
        }
    }

    private val mediaLoadProgressStub by unsafeLazy {
        itemView.find<ViewStub>(R.id.progress)
    }
    private val mediaLoadProgress by unsafeLazy {
        mediaLoadProgressStub.isVisible = true
        itemView.find<ContentLoadingProgressBar>(R.id.progress)
    }

    lateinit var team: Team
        private set
    private var isItemSelected: Boolean = false
    private var couldItemBeSelected: Boolean = false
    private var isScouting: Boolean = false

    private var isMediaLoaded: Boolean = false
        set(value) {
            field = value
            updateProgressVisibility()
        }

    init {
        recyclerView.addOnScrollListener(ScrollListener(this))

        media.setOnLongClickListenerCompat(this)
        newScout.setOnClickListener(this)
        newScout.setOnLongClickListenerCompat(this)
        itemView.setOnClickListener(this)
    }

    fun bind(
            team: Team,
            isItemSelected: Boolean,
            couldItemBeSelected: Boolean,
            isScouting: Boolean
    ) {
        this.team = team
        this.isItemSelected = isItemSelected
        this.couldItemBeSelected = couldItemBeSelected
        this.isScouting = isScouting

        setTeamNumber()
        setTeamName()
        updateItemStatus()
    }

    private fun setTeamNumber() {
        number.text = String.format(Locale.getDefault(), "%d", team.number)
    }

    private fun setTeamName() {
        name.text = if (team.name?.isNotBlank() == true) team.name else unknownName
    }

    private fun updateItemStatus() {
        isMediaLoaded = isItemSelected
        updateProgressVisibility()
        getTeamMediaRequestBuilder(isItemSelected, media.context, team)
                .listener(mediaLoadProgressListener)
                .into(media)
        ViewCompat.setTransitionName(media, team.id)

        newScout.animatePopReveal(!couldItemBeSelected)
        itemView.isActivated = !isItemSelected && !couldItemBeSelected && isScouting
        itemView.isSelected = isItemSelected
    }

    private fun updateProgressVisibility() {
        if (isMediaLoaded || recyclerView.isScrolling) {
            if (!mediaLoadProgressStub.isGone) mediaLoadProgress.hide(true)
        } else {
            mediaLoadProgress.show()
        }
    }

    override fun onClick(v: View) {
        if (!isItemSelected && !couldItemBeSelected) {
            (itemView.context as TeamSelectionListener)
                    .onTeamSelected(getScoutBundle(team, v.id == R.id.newScout), media)
        }
    }

    override fun onLongClick(v: View): Boolean {
        when {
            v.id == R.id.media -> TeamDetailsDialog.show(fragment.childFragmentManager, team)
            v.id == R.id.newScout -> TeamTemplateSelectorDialog.show(
                    fragment.childFragmentManager, team)
            else -> return false
        }
        return true
    }

    private class ScrollListener(holder: TeamViewHolder) : RecyclerView.OnScrollListener() {
        private val ref = WeakReference(holder)

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            ref.get()?.updateProgressVisibility()
        }
    }

    companion object {
        @SuppressLint("CheckResult") // Used in prefetch and standard load
        fun getTeamMediaRequestBuilder(
                isItemSelected: Boolean,
                context: Context,
                team: Team
        ): RequestBuilder<Drawable> = if (isItemSelected) {
            // TODO https://github.com/bumptech/glide/pull/3308
            // Glide.with(context).load(R.drawable.ic_check_circle_grey_56dp)
            Glide.with(context)
                    .load(null as String?)
                    .apply(RequestOptions.placeholderOf(R.drawable.ic_check_circle_grey_56dp))
        } else {
            Glide.with(context)
                    .load(team.media)
                    .apply(RequestOptions.circleCropTransform()
                                   .error(RC.drawable.ic_person_grey_96dp))
                    .transition(DrawableTransitionOptions.withCrossFade())
        }

        private val RecyclerView.isScrolling get() = scrollState != RecyclerView.SCROLL_STATE_IDLE
    }
}
