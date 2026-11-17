package com.campusoverflow.bootstrap.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusoverflow.identity.api.IdentityApi;
import com.campusoverflow.identity.api.UserSummary;
import com.campusoverflow.identity.application.AdminUserService;
import com.campusoverflow.identity.application.CourseService;
import com.campusoverflow.identity.application.CreateCourseCommand;
import com.campusoverflow.identity.application.CreateStaffCommand;
import com.campusoverflow.identity.application.RegisterUserCommand;
import com.campusoverflow.identity.application.UserRegistrationService;
import com.campusoverflow.identity.domain.CourseRole;
import com.campusoverflow.qa.application.command.AnswerCommandService;
import com.campusoverflow.qa.application.command.CommentService;
import com.campusoverflow.qa.application.command.PostCommentCommand;
import com.campusoverflow.qa.application.command.PostQuestionCommand;
import com.campusoverflow.qa.application.command.QuestionCommandService;
import com.campusoverflow.qa.application.command.VoteService;
import com.campusoverflow.qa.domain.TargetType;
import com.campusoverflow.reputation.application.BountyService;
import com.campusoverflow.shared.security.Actor;
import com.campusoverflow.shared.security.Role;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

/**
 * 演示数据种子契约：重复启动不能重复插入，首次启动的账号角色与后续业务动作的身份必须正确。
 *
 * <p>这里只 mock 端口，不启动 Spring 容器、不需要数据库或 Docker。seed 的初始积分并不由本类硬编码
 * 写入，而是来自注册链路与后续投票 / 悬赏动作，因此「初始积分正确」通过断言这些动作的发起者与分值
 * 来守护：投票一律 +1、悬赏由教师发起且为 50 分 7 天，没有任何绕过应用服务直接加分的调用。
 */
class DemoDataSeederTest {

    private final IdentityApi identity = mock(IdentityApi.class);
    private final UserRegistrationService registration = mock(UserRegistrationService.class);
    private final AdminUserService admins = mock(AdminUserService.class);
    private final CourseService courses = mock(CourseService.class);
    private final QuestionCommandService questions = mock(QuestionCommandService.class);
    private final AnswerCommandService answers = mock(AnswerCommandService.class);
    private final VoteService votes = mock(VoteService.class);
    private final CommentService comments = mock(CommentService.class);
    private final BountyService bounties = mock(BountyService.class);

    private final DemoDataSeeder seeder = new DemoDataSeeder(identity, registration, admins, courses,
            questions, answers, votes, comments, bounties);

    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @BeforeEach
    void noAdminYet() {
        when(identity.findByDisplayName("系统管理员")).thenReturn(Optional.empty());
    }

    @Test
    void 已有管理员时重复启动不会再插入任何演示数据() {
        when(identity.findByDisplayName("系统管理员"))
                .thenReturn(Optional.of(mock(UserSummary.class)));

        seeder.run(args);

        verify(identity).findByDisplayName("系统管理员");
        verifyNoInteractions(registration, admins, courses, questions, answers, votes, comments, bounties);
    }

    @Test
    void 首次启动按角色创建六个账号且学生全部通过审核() {
        seeder.run(args);

        ArgumentCaptor<Actor> staffActors = ArgumentCaptor.forClass(Actor.class);
        ArgumentCaptor<CreateStaffCommand> staff = ArgumentCaptor.forClass(CreateStaffCommand.class);
        verify(admins, times(3)).createStaff(staffActors.capture(), staff.capture());

        // 第一个管理员由虚拟系统主体引导创建，之后两个职员由真实管理员创建
        assertThat(staffActors.getAllValues())
                .extracting(Actor::role)
                .containsExactly(Role.ADMIN, Role.ADMIN, Role.ADMIN);
        assertThat(staffActors.getAllValues().get(0).userId()).isZero();
        assertThat(staffActors.getAllValues().get(1).displayName()).isEqualTo("系统管理员");

        assertThat(staff.getAllValues())
                .extracting(CreateStaffCommand::role)
                .containsExactly(Role.ADMIN, Role.TEACHER, Role.TA);
        assertThat(staff.getAllValues())
                .extracting(CreateStaffCommand::username)
                .containsExactly("admin01", "T2001", "TA3001");
        assertThat(staff.getAllValues())
                .allSatisfy(c -> assertThat(c.password()).isEqualTo(DemoDataSeeder.PASSWORD));
        assertThat(staff.getAllValues())
                .extracting(CreateStaffCommand::email)
                .containsExactly("admin@campus.edu.cn", "wang@campus.edu.cn", "li@campus.edu.cn");

        ArgumentCaptor<RegisterUserCommand> students = ArgumentCaptor.forClass(RegisterUserCommand.class);
        verify(registration, times(3)).register(students.capture());
        assertThat(students.getAllValues())
                .extracting(RegisterUserCommand::username)
                .containsExactly("2023001", "2023002", "2023003");
        assertThat(students.getAllValues())
                .allSatisfy(c -> assertThat(c.password()).isEqualTo(DemoDataSeeder.PASSWORD));

        ArgumentCaptor<Actor> verifier = ArgumentCaptor.forClass(Actor.class);
        verify(admins, times(3)).verify(verifier.capture(), anyLong());
        assertThat(verifier.getAllValues())
                .allSatisfy(a -> assertThat(a.role()).isEqualTo(Role.ADMIN));
    }

    @Test
    void 课程成员按教师助教学生角色装配且助教被授予课程内助教权限() {
        seeder.run(args);

        ArgumentCaptor<Actor> creator = ArgumentCaptor.forClass(Actor.class);
        ArgumentCaptor<CreateCourseCommand> course = ArgumentCaptor.forClass(CreateCourseCommand.class);
        verify(courses).create(creator.capture(), course.capture());
        assertThat(creator.getValue().role()).isEqualTo(Role.TEACHER);
        assertThat(course.getValue().code()).isEqualTo("CS2001");
        assertThat(course.getValue().name()).isEqualTo("数据结构");

        ArgumentCaptor<Actor> joiners = ArgumentCaptor.forClass(Actor.class);
        verify(courses, times(4)).join(joiners.capture(), anyLong());
        assertThat(joiners.getAllValues())
                .extracting(Actor::role)
                .containsExactly(Role.TA, Role.STUDENT, Role.STUDENT, Role.STUDENT);

        ArgumentCaptor<Actor> assigner = ArgumentCaptor.forClass(Actor.class);
        ArgumentCaptor<CourseRole> granted = ArgumentCaptor.forClass(CourseRole.class);
        verify(courses).assignRole(assigner.capture(), anyLong(), anyLong(), granted.capture());
        assertThat(assigner.getValue().role()).isEqualTo(Role.TEACHER);
        assertThat(granted.getValue()).isEqualTo(CourseRole.TA);
    }

    @Test
    void 演示链路的提问回答投票认证采纳评论悬赏身份与分值均正确() {
        seeder.run(args);

        ArgumentCaptor<PostQuestionCommand> posted = ArgumentCaptor.forClass(PostQuestionCommand.class);
        verify(questions, times(3)).post(any(Actor.class), posted.capture());
        assertThat(posted.getAllValues())
                .extracting(PostQuestionCommand::tags)
                .containsExactly(
                        java.util.List.of("数据结构", "红黑树"),
                        java.util.List.of("java", "spring-boot", "事务"),
                        java.util.List.of("mysql", "全文检索"));

        ArgumentCaptor<Actor> answerers = ArgumentCaptor.forClass(Actor.class);
        verify(answers, times(2)).submit(answerers.capture(), anyLong(), any(String.class));
        assertThat(answerers.getAllValues())
                .extracting(Actor::role)
                .containsExactly(Role.STUDENT, Role.TEACHER);

        ArgumentCaptor<Actor> voters = ArgumentCaptor.forClass(Actor.class);
        verify(votes, times(3)).cast(voters.capture(), any(TargetType.class), anyLong(), eq(1));
        assertThat(voters.getAllValues())
                .allSatisfy(a -> assertThat(a.role()).isEqualTo(Role.STUDENT));

        ArgumentCaptor<Actor> endorser = ArgumentCaptor.forClass(Actor.class);
        verify(answers).endorse(endorser.capture(), anyLong());
        assertThat(endorser.getValue().role()).isEqualTo(Role.TEACHER);

        ArgumentCaptor<Actor> accepter = ArgumentCaptor.forClass(Actor.class);
        verify(answers).accept(accepter.capture(), anyLong(), anyLong());
        assertThat(accepter.getValue().role()).isEqualTo(Role.STUDENT);

        ArgumentCaptor<PostCommentCommand> comment = ArgumentCaptor.forClass(PostCommentCommand.class);
        verify(comments).post(any(Actor.class), comment.capture());
        assertThat(comment.getValue().targetType()).isEqualTo(TargetType.ANSWER);
        assertThat(comment.getValue().parentId()).isNull();
        assertThat(comment.getValue().body()).contains("叔红变色、叔黑旋转");

        // 悬赏是唯一直接消耗积分的动作：教师发起，50 分 / 7 天，学生不得代发
        ArgumentCaptor<Actor> bountyOwner = ArgumentCaptor.forClass(Actor.class);
        verify(bounties).open(bountyOwner.capture(), anyLong(), eq(50), eq(7));
        assertThat(bountyOwner.getValue().role()).isEqualTo(Role.TEACHER);
    }
}
