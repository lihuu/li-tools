"""Exercise JAR and Native skillkit with synthetic repositories and isolated homes."""

from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile

workspace = Path(__file__).resolve().parents[1]
jar = workspace / 'skillkit/target/skillkit.jar'
binary = workspace / 'skillkit/target' / ('skillkit.exe' if os.name == 'nt' else 'skillkit')

for runtime in ('JAR', 'NATIVE'):
    with tempfile.TemporaryDirectory(prefix='skillkit-accept-') as temporary:
        root = Path(temporary)
        home, repository = root/'home', root/'repository'
        target = home/'.agents/skills'
        base = ['java', '-Duser.home='+str(home), '-jar', str(jar)] if runtime == 'JAR' else [str(binary), '-Duser.home='+str(home)]
        environment = dict(os.environ, NO_COLOR='')
        for key in ('CLAUDE_CONFIG_DIR', 'HERMES_HOME', 'XDG_CONFIG_HOME', 'SKILLKIT_REPOSITORY'):
            environment.pop(key, None)
        def run(*arguments, expected=0, repo=repository):
            result = subprocess.run([*base, '--repository', str(repo), *arguments], cwd=root,
                                    env=environment, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (runtime, arguments, result.returncode, result.stdout, result.stderr)
            assert '\x1b' not in result.stdout+result.stderr
            return result.stdout
        run('--help')
        run('--version')
        run('--unknown', expected=2)
        for obsolete in ('init', 'create', 'import', 'track', 'untrack', 'add', 'check', 'catalog'):
            run(obsolete, expected=2)
        (repository/'skills/own').mkdir(parents=True)
        (repository/'skills/own/SKILL.md').write_text('own one')
        external=root/'external/skills'
        for name in ('copied', 'followed'):
            (external/name).mkdir(parents=True)
            (external/name/'SKILL.md').write_text(name+' one')
        shutil.copytree(external/'copied', repository/'skills/copied')
        run('install', 'own', 'copied', '--jobs', '2')
        run('install', 'followed', '--repo', str(root/'external'))
        assert not (repository/'skills/followed').exists()
        assert len(json.loads((repository/'skillkit.json').read_text())['upstreams'])==1
        roots=[home/path for path in ('.agents/skills', '.claude/skills', '.qwen/skills',
               '.cursor/skills', '.gemini/skills', '.hermes/skills', '.config/opencode/skills',
               '.gemini/antigravity/skills')]
        for directory in roots:
            for name in ('own', 'copied', 'followed'):
                assert (directory/name/'SKILL.md').read_text()==name+' one'
        (repository/'skills/own/SKILL.md').write_text('own two')
        (external/'copied/SKILL.md').write_text('copied upstream two')
        (external/'followed/SKILL.md').write_text('followed two')
        run('update', '--jobs', '2')
        for directory in roots:
            assert (directory/'own/SKILL.md').read_text()=='own two'
            assert (directory/'copied/SKILL.md').read_text()=='copied one'
            assert (directory/'followed/SKILL.md').read_text()=='followed two'
        assert not (repository/'skills/followed').exists()
        installed=json.loads(run('list', '--json'))
        assert {item['name'] for item in installed}=={'own', 'copied', 'followed'}
        run('install', 'own', '--symlink')
        assert all((directory/'own').is_symlink() for directory in roots)
        run('uninstall', 'own')
        assert all(not (directory/'own').exists() for directory in roots)
        assert (repository/'skills/own/SKILL.md').read_text()=='own two'
        run('install', 'own')
        (target/'own/SKILL.md').write_text('local installation edit')
        run('update', 'own', expected=1)
        assert (target/'own/SKILL.md').read_text()=='local installation edit'
        run('uninstall', 'copied', 'followed')
        assert (repository/'skills/copied/SKILL.md').exists()
        assert all(not (directory/'followed').exists() for directory in roots)

        remote=root/'git-source'
        (remote/'skills/git-skill').mkdir(parents=True)
        def git(*arguments):
            result=subprocess.run(['git','-c','user.name=Skillkit Acceptance','-c','user.email=skillkit@example.invalid',
                                   '-c','commit.gpgsign=false','-c','core.hooksPath='+str(root/'no-hooks'),*arguments],
                                  cwd=remote, capture_output=True, text=True, timeout=20)
            assert result.returncode==0, result.stderr
        git('init','-b','main')
        (remote/'skills/git-skill/SKILL.md').write_text('git one')
        (remote/'README.md').write_text('unrelated repository documentation')
        (remote/'README-link').symlink_to('README.md')
        git('add','skills','README.md','README-link'); git('commit','-m','one')
        run('install','git-skill','--repo',remote.as_uri(),'--ref','main','--symlink')
        previous=(target/'git-skill').resolve()
        (remote/'skills/git-skill/SKILL.md').write_text('git two')
        git('add','skills'); git('commit','-m','two')
        run('update','git-skill','--jobs','2')
        assert (target/'git-skill/SKILL.md').read_text()=='git two'
        assert (previous/'SKILL.md').read_text()=='git one'
        assert (home/'.cache/skillkit').exists()

        second=root/'second-repository'
        (second/'skills/other').mkdir(parents=True)
        (second/'skills/other/SKILL.md').write_text('other')
        shared=target
        first=subprocess.Popen([*base,'--repository',str(repository),'install','copied'],cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        second_process=subprocess.Popen([*base,'--repository',str(second),'install','other'],cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        for process in (first,second_process):
            out,err=process.communicate(timeout=30)
            assert process.returncode==0,(out,err)
        state=json.loads((shared/'.skillkit-state.json').read_text())
        assert {item['name'] for item in state['installations']}>={'copied','other'}
        assert not list(shared.glob('.skillkit-new-*'))
        print(runtime+': four commands/global install/local content/metadata follow/parallel update/ownership/symlink/Git snapshot/cross-process state PASS',flush=True)
