package eu.kanade.tachiyomi.ui.extension

import android.view.View
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.databinding.ExtensionCardItemBinding
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.base.holder.SlicedHolder
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import io.github.mthli.slice.Slice

class ExtensionHolder(view: View, override val adapter: ExtensionAdapter) :
    BaseFlexibleViewHolder(view, adapter),
    SlicedHolder {
    private val binding = ExtensionCardItemBinding.bind(view)

    override val slice =
        Slice(binding.card).apply {
            setColor(adapter.cardBackground)
        }

    override val viewToSlice: View
        get() = binding.card

    init {
        binding.extButton.setOnClickListener {
            adapter.buttonClickListener.onButtonClick(bindingAdapterPosition)
        }
        binding.cancelButton.setOnClickListener {
            adapter.buttonClickListener.onCancelButtonClick(bindingAdapterPosition)
        }
    }

    fun bind(item: ExtensionItem) {
        val extension = item.extension
        setCardEdges(item)

        // Set source name
        binding.extTitle.text = extension.name
        binding.version.text = extension.versionName
        binding.lang.text =
            if (extension !is Extension.Untrusted) {
                LocaleHelper.getSourceDisplayName(extension.lang, itemView.context)
            } else {
                itemView.context.getString(R.string.ext_untrusted).uppercase()
            }

        GlideApp.with(itemView.context).clear(binding.image)
        if (extension is Extension.Available) {
            GlideApp.with(itemView.context)
                .load(extension.iconUrl)
                .into(binding.image)
        } else {
            extension.getApplicationIcon(itemView.context)?.let { binding.image.setImageDrawable(it) }
        }
        bindButtons(item)
    }

    @Suppress("ResourceType")
    fun bindButtons(item: ExtensionItem) =
        with(binding.extButton) {
            setTextColor(context.getResourceColor(androidx.appcompat.R.attr.colorAccent))

            val extension = item.extension

            val installStep = item.installStep
            text =
                when (installStep) {
                    InstallStep.Pending -> context.getString(R.string.ext_pending)
                    InstallStep.Downloading -> context.getString(R.string.ext_downloading)
                    InstallStep.Installing -> context.getString(R.string.ext_installing)
                    InstallStep.Installed -> context.getString(R.string.ext_installed)
                    InstallStep.Error -> context.getString(R.string.action_retry)
                    InstallStep.Idle -> {
                        when (extension) {
                            is Extension.Installed -> {
                                when {
                                    extension.hasUpdate -> {
                                        context.getString(R.string.ext_update)
                                    }
                                    extension.isObsolete -> {
                                        setTextColor(context.getResourceColor(androidx.appcompat.R.attr.colorError))
                                        context.getString(R.string.ext_obsolete)
                                    }
                                    extension.isUnofficial -> {
                                        setTextColor(context.getResourceColor(androidx.appcompat.R.attr.colorError))
                                        context.getString(R.string.ext_unofficial)
                                    }
                                    extension.isRedundant -> {
                                        setTextColor(context.getResourceColor(androidx.appcompat.R.attr.colorError))
                                        context.getString(R.string.ext_redundant)
                                    }
                                    else -> {
                                        context.getString(R.string.ext_details).plusRepo(extension)
                                    }
                                }
                            }
                            is Extension.Untrusted -> context.getString(R.string.ext_trust)
                            is Extension.Available -> context.getString(R.string.ext_install)
                        }
                    }
                }

            val isIdle = installStep == InstallStep.Idle || installStep == InstallStep.Error
            binding.cancelButton.isVisible = !isIdle
            isEnabled = isIdle
            isClickable = isIdle
        }

    // SY -->
    private fun String.plusRepo(extension: Extension): String {
        return if (extension is Extension.Available) {
            when (extension.repoUrl) {
                else -> {
                    this +
                        if (this.isEmpty()) {
                            ""
                        } else {
                            " • "
                        } + itemView.context.getString(R.string.repo_source)
                }
            }
        } else {
            this
        }
    }
    // SY <--
}
