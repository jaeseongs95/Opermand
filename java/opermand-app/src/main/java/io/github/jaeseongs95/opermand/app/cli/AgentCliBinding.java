package io.github.jaeseongs95.opermand.app.cli;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.TargetRevision;

/** Which requests a history lists: one provider on one target revision and profile (2.x {@code agent-cli-state.mjs:33-43}). */
public record AgentCliBinding(AgentCliProviderId provider, TargetId targetId, TargetRevision targetRevision, ProfileAlias profile) {

    public AgentCliBinding {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetRevision, "targetRevision");
        Objects.requireNonNull(profile, "profile");
    }
}
