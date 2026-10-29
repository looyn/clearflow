package dev.clearflow.infra;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.common.AuthContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态流转审计：所有实体改状态都必须调用 record（含批量更新路径，最终都走领域 service）。
 * 不吞异常：写事件失败应让整个状态变更事务回滚，避免统计口径悄悄缺数据。
 */
@Service
public class StatusEventService {

    private final StatusEventMapper mapper;

    public StatusEventService(StatusEventMapper mapper) {
        this.mapper = mapper;
    }

    public void record(Long projectId, String kind, String entityId, String from, String to) {
        if (to == null || to.equals(from)) {
            return;
        }
        StatusEvent e = new StatusEvent();
        e.setProjectId(projectId);
        e.setKind(kind);
        e.setEntityId(entityId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setByMember(AuthContext.require().id());
        e.setAt(LocalDateTime.now());
        mapper.insert(e);
    }

    /** 项目内某类实体的全部流转事件（升序），供度量/统计一次性聚合 */
    public List<StatusEvent> list(Long projectId, String kind) {
        return mapper.selectList(new LambdaQueryWrapper<StatusEvent>()
                .eq(StatusEvent::getProjectId, projectId)
                .eq(StatusEvent::getKind, kind)
                .orderByAsc(StatusEvent::getAt)
                .orderByAsc(StatusEvent::getId));
    }

    /** 每个实体首次到达某状态的时刻（refId → 时间） */
    public Map<String, LocalDateTime> firstEnter(Long projectId, String kind, String toStatus) {
        Map<String, LocalDateTime> out = new HashMap<>();
        list(projectId, kind).stream()
                .filter(e -> toStatus.equals(e.getToStatus()))
                .forEach(e -> out.putIfAbsent(e.getEntityId(), e.getAt()));
        return out;
    }
}
