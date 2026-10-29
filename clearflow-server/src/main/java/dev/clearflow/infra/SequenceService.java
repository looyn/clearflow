package dev.clearflow.infra;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务编号生成：sequence 表 FOR UPDATE 行锁自增，生成 T-328 / R-128 / B-242 等展示码
 * （编号规则与前端 mock 一致：前缀 + 序号，REL/DOC 不补零）
 */
@Service
public class SequenceService {

    private final SequenceMapper mapper;

    public SequenceService(SequenceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String next(String bizType) {
        Sequence seq = mapper.selectOne(new LambdaQueryWrapper<Sequence>()
                .eq(Sequence::getBizType, bizType)
                .last("FOR UPDATE"));
        long n;
        if (seq == null) {
            seq = new Sequence();
            seq.setBizType(bizType);
            n = 1;
            seq.setNextVal(2L);
            mapper.insert(seq);
        } else {
            n = seq.getNextVal();
            seq.setNextVal(n + 1);
            mapper.updateById(seq);
        }
        return format(bizType, n);
    }

    private String format(String bizType, long n) {
        return switch (bizType) {
            case "task" -> "T-" + n;
            case "requirement" -> "R-" + n;
            case "defect" -> "B-" + n;
            case "release" -> "REL-" + n;
            case "doc" -> "DOC-" + n;
            case "worklog" -> "W-" + n;
            default -> bizType + "-" + n;
        };
    }
}
