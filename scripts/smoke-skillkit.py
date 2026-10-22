"""Exercise JAR and Native skillkit with synthetic repositories and isolated homes."""

from pathlib import Path
import json
import os
import subprocess
import tempfile

workspace = Path(__file__).resolve().parents[1]
jar = workspace / 'skillkit/target/skillkit.jar'
binary = workspace / 'skillkit/target' / ('skillkit.exe' if os.name == 'nt' else 'skillkit')

for runtime in ('JAR', 'NATIVE'):
    with tempfile.TemporaryDirectory(prefix='skillkit-accept-') as temporary:
        root = Path(temporary)
        home, repository, target = root/'home', root/'repository', root/'target'
        base = ['java', '-Duser.home='+str(home), '-jar', str(jar)] if runtime == 'JAR' else [str(binary), '-Duser.home='+str(home)]
        environment = dict(os.environ, NO_COLOR='')
        def run(*arguments, expected=0, repo=repository):
            result = subprocess.run([*base, '--repository', str(repo), *arguments], cwd=root,
                                    env=environment, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, (runtime, arguments, result.returncode, result.stdout, result.stderr)
            assert '\x1b' not in result.stdout+result.stderr
            return result.stdout
        run('--help')
        run('--version')
        run('--unknown', expected=2)
        run('init')
        run('create', 'own')
        (repository/'skills/own/SKILL.md').write_text('own one')
        external=root/'external/skills'
        for name in ('copied','followed'):
            (external/name).mkdir(parents=True)
            (external/name/'SKILL.md').write_text(name+' one')
        run('import','copied','--source',str(root/'external'))
        run('track','followed','--source',str(root/'external'))
        assert not (repository/'skills/followed').exists()
        assert len(json.loads((repository/'skillkit.json').read_text())['upstreams'])==1
        run('add','own','copied','followed','--target-dir',str(target),'--jobs','2')
        for name in ('own','copied','followed'):
            assert (target/name/'SKILL.md').read_text()==name+' one'
        (repository/'skills/own/SKILL.md').write_text('own two')
        (external/'copied/SKILL.md').write_text('copied upstream two')
        (external/'followed/SKILL.md').write_text('followed two')
        checked=json.loads(run('check','--target-dir',str(target),'--json'))
        assert {item['name']: item['status'] for item in checked}=={'own':'outdated','copied':'current','followed':'outdated'}
        run('update','--target-dir',str(target),'--jobs','2')
        assert (target/'own/SKILL.md').read_text()=='own two'
        assert (target/'copied/SKILL.md').read_text()=='copied one'
        assert (target/'followed/SKILL.md').read_text()=='followed two'
        assert not (repository/'skills/followed').exists()
        links=root/'links'
        run('add','own','--target-dir',str(links),'--symlink')
        assert (links/'own').is_symlink()
        run('remove','own','--target-dir',str(links))
        assert (repository/'skills/own/SKILL.md').read_text()=='own two'
        (target/'own/SKILL.md').write_text('local installation edit')
        run('update','own','--target-dir',str(target),expected=1)
        assert (target/'own/SKILL.md').read_text()=='local installation edit'
        run('remove','copied','followed','--target-dir',str(target))
        assert (repository/'skills/copied/SKILL.md').exists()
        assert not (target/'followed').exists()

        remote=root/'git-source'
        (remote/'skills/git-skill').mkdir(parents=True)
        def git(*arguments):
            result=subprocess.run(['git','-c','user.name=Skillkit Acceptance','-c','user.email=skillkit@example.invalid',
                                   '-c','commit.gpgsign=false','-c','core.hooksPath='+str(root/'no-hooks'),*arguments],
                                  cwd=remote, capture_output=True, text=True, timeout=20)
            assert result.returncode==0, result.stderr
        git('init','-b','main')
        (remote/'skills/git-skill/SKILL.md').write_text('git one')
        git('add','skills'); git('commit','-m','one')
        run('add','git-skill','--source',remote.as_uri(),'--ref','main','--target-dir',str(links),'--symlink')
        previous=(links/'git-skill').resolve()
        (remote/'skills/git-skill/SKILL.md').write_text('git two')
        git('add','skills'); git('commit','-m','two')
        run('update','--target-dir',str(links),'--jobs','2')
        assert (links/'git-skill/SKILL.md').read_text()=='git two'
        assert (previous/'SKILL.md').read_text()=='git one'
        assert (home/'.cache/skillkit').exists()

        second=root/'second-repository'
        run('init',repo=second)
        run('create','other',repo=second)
        shared=root/'shared-target'
        first=subprocess.Popen([*base,'--repository',str(repository),'add','copied','--target-dir',str(shared)],cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        second_process=subprocess.Popen([*base,'--repository',str(second),'add','other','--target-dir',str(shared)],cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        for process in (first,second_process):
            out,err=process.communicate(timeout=30)
            assert process.returncode==0,(out,err)
        state=json.loads((shared/'.skillkit-state.json').read_text())
        assert {item['name'] for item in state['installations']}=={'copied','other'}
        assert not list(shared.glob('.skillkit-new-*'))
        print(runtime+': local/import/metadata follow/parallel update/ownership/symlink/Git snapshot/cross-process state PASS',flush=True)
