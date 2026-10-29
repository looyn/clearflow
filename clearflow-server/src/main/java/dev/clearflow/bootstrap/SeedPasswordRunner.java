package dev.clearflow.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 种子成员初始化密码：data.sql 中 password_hash 留 NULL 的成员，
 * 启动时统一写入 BCrypt("123456")（幂等：只处理 NULL 行）
 */
@Slf4j
@Component
public class SeedPasswordRunner implements CommandLineRunner {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;

    public SeedPasswordRunner(MemberMapper memberMapper, PasswordEncoder passwordEncoder) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        var pending = memberMapper.selectList(new LambdaQueryWrapper<Member>()
                .isNull(Member::getPasswordHash));
        if (pending.isEmpty()) {
            return;
        }
        String hash = passwordEncoder.encode("123456");
        for (Member member : pending) {
            member.setPasswordHash(hash);
            memberMapper.updateById(member);
        }
        log.info("已为 {} 个种子成员初始化默认密码（123456）", pending.size());
    }
}
