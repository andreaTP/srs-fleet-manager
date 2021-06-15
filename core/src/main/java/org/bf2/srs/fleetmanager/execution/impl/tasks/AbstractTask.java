package org.bf2.srs.fleetmanager.execution.impl.tasks;

import org.bf2.srs.fleetmanager.execution.manager.Task;
import org.bf2.srs.fleetmanager.execution.manager.TaskSchedule;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

@NoArgsConstructor
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public abstract class AbstractTask implements Task {

    @EqualsAndHashCode.Include
    protected String id;

    protected String type;

    protected TaskSchedule schedule;

    protected AbstractTask(TaskType type) {
        requireNonNull(type);
        this.id = UUID.randomUUID().toString();
        this.type = type.name();
        this.schedule = TaskSchedule.builder().build();
    }
}